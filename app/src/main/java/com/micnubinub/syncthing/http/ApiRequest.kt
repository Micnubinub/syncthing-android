package com.micnubinub.syncthing.http


import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.net.toUri
import com.micnubinub.syncthing.service.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.MalformedURLException
import java.net.URL
import java.nio.charset.StandardCharsets

abstract class ApiRequest internal constructor(
    private val context: Context?,
    url: URL,
    private val path: String?,
    private val apiKey: String?
) {
    protected val TAG: String
        get() = this::class.simpleName ?: "Syncthing"
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * The in-flight request, cancelled via [cancelRequest] (used by
     * [PollWebGuiAvailableTask] when polling is aborted).
     */
    @Volatile
    private var activeCall: Call? = null

    // The app only ever talks to the local syncthing instance. Pin the connection to the
    // loopback interface regardless of the configured GUI listen address (which is 0.0.0.0
    // when remote access is enabled), keeping the API key and config off any routable interface.
    private val mUrl: URL = forceLoopbackHost(url)

    fun buildUri(params: MutableMap<String, String?>): Uri? {
        val uriBuilder = mUrl.toString().toUri()
            .buildUpon()
            .path(path)
        for (entry in params.entries) {
            uriBuilder.appendQueryParameter(entry.key, entry.value)
        }
        return uriBuilder.build()
    }

    /**
     * Enqueues the request, then returns success status and response string.
     */
    fun connect(
        requestMethod: String, uri: Uri?, requestBody: String?,
        listener: OnSuccessListener?, errorListener: OnErrorListener?
    ) {
        val uriString = uri?.toString()
        if (uriString.isNullOrEmpty()) {
            errorListener?.onError(ApiError.Network(IOException("Failed to build request URI")))
            return
        }
        val requestBuilder = Request.Builder().url(uriString)
        apiKey?.let { requestBuilder.header(HEADER_API_KEY, it) }
        val body = requestBody?.toRequestBody(null)
            ?: ByteArray(0).toRequestBody(null)
        requestBuilder.method(requestMethod, if (requestMethod == "GET") null else body)

        val client = SyncthingHttpClients.get(context)

        activeCall = client.newCall(requestBuilder.build())
        activeCall?.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (activeCall === call) {
                    activeCall = null
                }
                val error =
                    if (call.isCanceled()) ApiError.Cancelled(e) else ApiError.Network(e)
                mainScope.launch {
                    if (errorListener != null) {
                        errorListener.onError(error)
                    } else {
                        Log.w(TAG, "Request to $uri failed, msg=${e.message}", e)
                    }
                }
            }

            override fun onResponse(call: Call, response: Response) {
                if (activeCall === call) {
                    activeCall = null
                }
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val error = ApiError.Http(
                            resp.code, resp.body.string(), resp.message
                        )
                        mainScope.launch {
                            errorListener?.onError(error) ?: Log.w(
                                TAG,
                                "Request to $uri failed, code=${resp.code}, msg=${resp.message}"
                            )
                        }
                        return
                    }
                    val body = responseBodyToString(resp)
                    mainScope.launch {
                        listener?.onSuccess(body)
                    }
                }
            }
        })
    }

    /**
     * Cancels the currently in-flight request, if any, and the per-instance callback scope
     * so no response/error callbacks are delivered after cancellation.
     */
    fun cancelRequest() {
        mainScope.cancel()
        activeCall?.cancel()
    }

    /**
     * Decodes the response body using the same charset heuristic as the former Volley stack:
     * an explicit charset from the Content-Type header wins, JSON defaults to UTF-8, anything
     * else falls back to ISO-8859-1 (which byte-round-trips binary payloads such as the support
     * bundle).
     */
    private fun responseBodyToString(response: Response): String {
        val contentType = response.body.contentType()
        val explicitCharset = contentType?.charset()
        val isUtf8Json = contentType?.type == "application" && contentType.subtype == "json"
        val charset = when {
            explicitCharset != null -> explicitCharset
            isUtf8Json -> StandardCharsets.UTF_8
            else -> StandardCharsets.ISO_8859_1
        }
        return response.body.byteStream().reader(charset).use { it.readText() }
    }

    companion object {
        /**
         * The name of the HTTP header used for the syncthing API key.
         */
        private const val HEADER_API_KEY = "X-API-Key"

        /**
         * Rewrites the host of the given URL to 127.0.0.1, preserving the scheme and port. The port
         * comes from the configured GUI listen address; falls back to the default web GUI port.
         * 
         * 
         * Forcing loopback is intentional and security-relevant, not merely a convenience:
         * 
         *  * The app only ever sets the GUI address to `127.0.0.1` or `0.0.0.0` (the
         * "listen on all interfaces" setting). `0.0.0.0` always includes loopback, so the
         * local instance is reachable on `127.0.0.1` in every app-managed config.
         *  * It keeps the API key and configuration off any routable interface.
         *  * It is the precondition that makes the two TLS relaxations safe: disabling hostname
         * verification and falling back to the OS trust store / user-installed CAs
         * ([SyncthingTrustManager]). On loopback there is no network position for a MITM to
         * occupy, so neither relaxation can be abused.
         * 
         * Do not "simplify" this by connecting to the configured address directly: `0.0.0.0` is
         * not a valid destination (and modern WebView blocks it), and targeting a routable address
         * would break the trust model above.
         */
        internal fun forceLoopbackHost(url: URL): URL {
            try {
                val port =
                    if (url.port != -1) url.port else Constants.DEFAULT_WEBGUI_TCP_PORT
                val rewritten = URL(
                    url.protocol,
                    "127.0.0.1",
                    port,
                    url.file
                )
                require(rewritten.host == "127.0.0.1") {
                    "forceLoopbackHost: host must be 127.0.0.1 but was '${rewritten.host}'"
                }
                return rewritten
            } catch (e: MalformedURLException) {
                throw IllegalStateException(
                    "forceLoopbackHost: Cannot rewrite URL to loopback: $url", e
                )
            }
        }
    }
}
