package com.micnubinub.syncthing.http

import android.content.Context
import com.micnubinub.syncthing.http.SyncthingHttpClients.inFlightCalls
import com.micnubinub.syncthing.service.Constants
import okhttp3.Call
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManager

/**
 * Holds the process-wide [OkHttpClient] singleton shared by all Syncthing REST API
 * requests, pinned to the certificate of the local syncthing instance via
 * [SyncthingTrustManager] and with hostname verification disabled.
 *
 * Call [invalidate] after the HTTPS certificate is replaced so the next request
 * rebuilds the client against the new certificate.
 */
object SyncthingHttpClients {
    /**
     * Timeouts for the shared client, used by regular (short-lived) API requests.
     * The events long-poll ([EventsPoller]) derives a client with a longer
     * read timeout so a quiet poll is not aborted.
     */
    private const val CONNECT_TIMEOUT = 15L
    private const val READ_TIMEOUT = 30L
    private const val WRITE_TIMEOUT = 15L

    @Volatile
    private var client: OkHttpClient? = null

    /**
     * Calls handed out by [get] that have not finished yet.
     *
     * Every request talks to the one local core, so once that core is being shut down no
     * outstanding request can still succeed. [cancelInFlightCalls] ends them instead of
     * letting each hold an OkHttp dispatcher thread until it times out.
     */
    private val inFlightCalls = ConcurrentHashMap.newKeySet<Call>()

    /**
     * Tracks [call] until the caller reports it finished, so [cancelInFlightCalls] can
     * end it. [untrack] must be called once the call is done.
     */
    internal fun track(call: Call): Call {
        inFlightCalls += call
        return call
    }

    /**
     * Stops tracking [call], see [track].
     */
    internal fun untrack(call: Call) {
        inFlightCalls -= call
    }

    /**
     * Cancels every request that is still in flight, see [inFlightCalls]. Call this when
     * the core goes away, before issuing any request that must still reach it.
     */
    internal fun cancelInFlightCalls() {
        inFlightCalls.forEach { it.cancel() }
        inFlightCalls.clear()
    }

    @Synchronized
    fun get(context: Context?): OkHttpClient {
        client?.let { return it }
        val builder = OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT, TimeUnit.SECONDS)
            .connectionSpecs(listOf(ConnectionSpec.MODERN_TLS))
            .retryOnConnectionFailure(true)
            // retryOnConnectionFailure only covers connection establishment problems and
            // stale pooled connections. Read/write timeouts on a live connection are
            // retried by [ApiRequest] instead, where the backoff does not block a thread.
        val trustManager =
            SyncthingTrustManager(Constants.getHttpsCertFile(context))
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(
            null,
            arrayOf<TrustManager>(trustManager),
            SecureRandom()
        )
        builder.sslSocketFactory(sslContext.socketFactory, trustManager)
        // The certificate's SAN/CN need not match 127.0.0.1 (a user-supplied CA cert is
        // typically issued for a real hostname), so standard hostname verification is replaced
        // with an explicit loopback gate. Any attempt to reuse this shared client for a
        // non-loopback host will fail here rather than silently skipping hostname checks.
        // Trust is still enforced by SyncthingTrustManager: the self-signed pin first, then
        // the OS trust store.
        builder.hostnameVerifier { hostname: String?, _: SSLSession? ->
            hostname == "127.0.0.1"
        }
        client = builder.build()
        return client as OkHttpClient
    }

    /**
     * Drops the cached client so it is rebuilt lazily on the next request. Called whenever
     * the app changes the HTTPS certificate file (certificate replacement/reset, including
     * rollback to the previous certificate). [SyncthingTrustManager] also re-reads the file
     * when it changes, which covers certificates the core regenerates on its own.
     */
    @Synchronized
    fun invalidate() {
        client = null
    }
}
