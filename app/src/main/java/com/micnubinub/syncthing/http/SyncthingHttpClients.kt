package com.micnubinub.syncthing.http

import android.content.Context
import android.util.Log
import com.micnubinub.syncthing.http.SyncthingHttpClients.INITIAL_TIMEOUT_BACKOFF_MS
import com.micnubinub.syncthing.http.SyncthingHttpClients.invalidate
import com.micnubinub.syncthing.service.Constants
import okhttp3.ConnectionSpec
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.net.SocketTimeoutException
import java.security.SecureRandom
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
    private const val TAG = "SyncthingHttpClients"

    /**
     * Timeouts for the shared client, used by regular (short-lived) API requests.
     * The events long-poll ([EventsPoller]) derives a client with a longer
     * read timeout so a quiet poll is not aborted.
     */
    private const val CONNECT_TIMEOUT = 15L
    private const val READ_TIMEOUT = 30L
    private const val WRITE_TIMEOUT = 15L

    /**
     * Retries for read/write timeouts, applied by [SocketTimeoutRetryInterceptor]. Kept
     * small so a hung core still surfaces an error quickly. The backoff sleep runs on an
     * OkHttp dispatcher worker thread, so it is capped at a small fixed value to avoid
     * needlessly reducing dispatcher concurrency during stalls; it never delays a
     * [ApiRequest.cancelRequest] by more than [INITIAL_TIMEOUT_BACKOFF_MS] per attempt.
     */
    private const val MAX_TIMEOUT_RETRIES = 2
    private const val INITIAL_TIMEOUT_BACKOFF_MS = 100L

    @Volatile
    private var client: OkHttpClient? = null

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
            // stale pooled connections, not read/write timeouts on a live connection, so
            // add an interceptor that retries SocketTimeoutException with backoff. The
            // events long-poll client (derived via newBuilder) inherits this interceptor.
            .addInterceptor(SocketTimeoutRetryInterceptor())
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
     * Drops the cached client so it is rebuilt lazily on the next request. Must be called
     * whenever the HTTPS certificate file changes (certificate replacement/reset, including
     * rollback to the previous certificate), because the trust manager pins the certificate
     * that was on disk when the client was built.
     */
    @Synchronized
    fun invalidate() {
        client = null
    }

    /**
     * Retries requests that fail with a [SocketTimeoutException] using backoff.
     *
     * The local syncthing core occasionally stalls long enough to trip the read timeout
     * while it is busy (e.g. during index exchange); give it a couple of chances before
     * failing the request.
     *
     * Only idempotent GET requests are retried: a timeout on a mutating request is
     * ambiguous (the server may have already executed it), and replaying the request
     * body would risk duplicate execution.
     *
     * A cancelled call (see [ApiRequest.cancelRequest]) is never retried, keeping
     * cancellation prompt. The interceptor runs synchronously on an OkHttp dispatcher
     * worker thread, so the backoff is a small fixed sleep rather than a growing
     * exponential wait, to avoid needlessly reducing dispatcher concurrency during stalls.
     * The call is re-checked both before and after the sleep so a cancelled call returns
     * promptly once the wait completes.
     */
    private class SocketTimeoutRetryInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val retryable = request.method == "GET"
            var attempt = 0
            while (true) {
                try {
                    return chain.proceed(request)
                } catch (e: SocketTimeoutException) {
                    if (!retryable || attempt >= MAX_TIMEOUT_RETRIES ||
                        chain.call().isCanceled()
                    ) {
                        throw e
                    }
                    attempt++
                    Log.d(
                        TAG,
                        "Request to ${request.url} timed out, " +
                                "retry $attempt/$MAX_TIMEOUT_RETRIES in ${INITIAL_TIMEOUT_BACKOFF_MS}ms"
                    )
                    try {
                        // A sleeping worker thread cannot observe cancellation while blocked,
                        // so re-check status immediately after the sleep returns and bail out if
                        // the call was cancelled during the wait.
                        Thread.sleep(INITIAL_TIMEOUT_BACKOFF_MS)
                        if (chain.call().isCanceled()) {
                            throw SocketTimeoutException("Canceled while waiting to retry")
                        }
                    } catch (interrupted: InterruptedException) {
                        // Preserve the interrupt and fail with the original timeout.
                        Thread.currentThread().interrupt()
                        throw e
                    }
                }
            }
        }
    }
}
