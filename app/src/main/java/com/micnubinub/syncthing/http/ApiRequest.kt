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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.MalformedURLException
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

abstract class ApiRequest internal constructor(
    private val context: Context?,
    url: URL,
    private val path: String?,
    private val apiKey: String?
) {
    /**
     * The outcome of a request that was awaited to completion, see [connectAwait].
     */
    sealed interface ApiResult {
        data class Success(val body: String) : ApiResult
        data class Failure(val error: ApiError) : ApiResult
    }

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
        for ((key, value) in params) {
            uriBuilder.appendQueryParameter(key, value)
        }
        return uriBuilder.build()
    }

    /**
     * Enqueues the request, then returns success status and response string.
     *
     * Exactly one of [listener] or [errorListener] is invoked, including when the
     * response starts arriving and then breaks part way through: a caller that waits on
     * one of them (see [PollWebGuiAvailableTask.suspendPerformRequest]) would otherwise
     * wait for a result that is never produced.
     */
    fun connect(
        requestMethod: String, uri: Uri?, requestBody: String?,
        listener: OnSuccessListener?, errorListener: OnErrorListener?
    ) {
        enqueue(requestMethod, uri, requestBody, listener, errorListener, attempt = 0)
    }

    private fun enqueue(
        requestMethod: String, uri: Uri?, requestBody: String?,
        listener: OnSuccessListener?, errorListener: OnErrorListener?,
        attempt: Int
    ) {
        val call = newCall(requestMethod, uri, requestBody) ?: run {
            errorListener?.onError(ApiError.Network(IOException("Failed to build request URI")))
            return
        }

        activeCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                /**
                 * The local core occasionally stalls long enough to trip the read timeout
                 * while it is busy (e.g. during index exchange), so give it a couple of
                 * chances. Only idempotent GETs are retried: a timeout on a mutating request
                 * is ambiguous, and replaying it could execute it twice. The call stays
                 * owned and tracked during the backoff, so [cancelRequest] and
                 * [SyncthingHttpClients.cancelInFlightCalls] still end it.
                 */
                if (requestMethod == "GET" && e is SocketTimeoutException &&
                    attempt < MAX_TIMEOUT_RETRIES && !call.isCanceled()
                ) {
                    Log.d(TAG, "Request timed out, retry ${attempt + 1}/$MAX_TIMEOUT_RETRIES")
                    mainScope.launch {
                        delay(TIMEOUT_RETRY_BACKOFF_MS.milliseconds)
                        if (call.isCanceled()) {
                            deliverError(call, uri, errorListener, e, ApiError.Cancelled(e))
                            return@launch
                        }
                        if (activeCall === call) {
                            activeCall = null
                        }
                        SyncthingHttpClients.untrack(call)
                        enqueue(
                            requestMethod, uri, requestBody, listener, errorListener,
                            attempt + 1
                        )
                    }
                    return
                }
                deliverError(
                    call, uri, errorListener, e,
                    if (call.isCanceled()) ApiError.Cancelled(e) else ApiError.Network(e)
                )
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { resp ->
                        if (!resp.isSuccessful) {
                            val error = ApiError.Http(
                                resp.code, resp.body.string(), resp.message
                            )
                            deliverError(call, uri, errorListener, null, error)
                            return
                        }
                        val body = responseBodyToString(resp)
                        mainScope.launch {
                            listener?.onSuccess(body)
                        }
                    }
                } catch (e: IOException) {
                    // The status line arrived but the body did not finish, so this is a
                    // transport failure rather than an HTTP error. Reporting it is the
                    // only way the caller learns the request failed at all.
                    deliverError(call, uri, errorListener, e, ApiError.Network(e))
                } finally {
                    // Only now that the body has been consumed is this request no longer
                    // cancellable, so ownership is held for the whole of onResponse.
                    if (activeCall === call) {
                        activeCall = null
                    }
                    SyncthingHttpClients.untrack(call)
                }
            }
        })
    }

    /**
     * Delivers the single terminal failure result of a request, releasing its ownership
     * and keeping the log for callers that supplied no error listener.
     */
    private fun deliverError(
        call: Call,
        uri: Uri?,
        errorListener: OnErrorListener?,
        cause: IOException?,
        error: ApiError
    ) {
        if (activeCall === call) {
            activeCall = null
        }
        // A failed call is over, so it must leave the in-flight set here. The callback
        // style has no other place to do it: onFailure never reaches the try/finally of
        // onResponse, and a call that is never released keeps cancelInFlightCalls()
        // handing out cancellations for a request that finished long ago.
        SyncthingHttpClients.untrack(call)
        if (errorListener == null) {
            Log.w(TAG, "Request to $uri failed: $error", cause)
        } else {
            mainScope.launch { errorListener.onError(error) }
        }
    }

    /**
     * Performs the request and suspends until its outcome is known.
     *
     * Use this instead of [connect] whenever the caller acknowledges the change to the
     * user: the callback style reports nothing, so a caller that treats "request sent" as
     * "write persisted" reports success for a write that never landed.
     *
     * Exactly one [ApiResult] is returned, including when reading the response body
     * fails, so an awaiting caller can never be stranded. Cancelling the calling
     * coroutine discards the result and cancels the request itself.
     */
    suspend fun connectAwait(
        requestMethod: String, uri: Uri?, requestBody: String?
    ): ApiResult {
        val call = newCall(requestMethod, uri, requestBody)
            ?: return ApiResult.Failure(ApiError.Network(IOException("Failed to build request URI")))

        activeCall = call
        try {
            /**
             * The call is enqueued, not executed, so the caller is suspended rather than
             * blocked on [Call.execute]. A blocking execute() cannot observe cancellation,
             * so a caller that bounds this with a timeout - the shutdown POST, which runs
             * while the core lifecycle lock is held - would wait out the client's much
             * longer read timeout instead of its own deadline. Here cancellation resumes
             * the caller at once and cancels the call. A result that arrives after
             * cancellation is discarded, so a save that was abandoned mid-flight is never
             * reported as applied.
             */
            return suspendCancellableCoroutine { cont ->
                cont.invokeOnCancellation { call.cancel() }
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        if (call.isCanceled()) {
                            cont.resume(ApiResult.Failure(ApiError.Cancelled(e)))
                        } else {
                            Log.w(TAG, "Request to $uri failed, msg=${e.message}", e)
                            cont.resume(ApiResult.Failure(ApiError.Network(e)))
                        }
                    }

                    override fun onResponse(call: Call, response: Response) {
                        val result = try {
                            response.use { resp ->
                                if (!resp.isSuccessful) {
                                    ApiResult.Failure(
                                        ApiError.Http(resp.code, resp.body.string(), resp.message)
                                    )
                                } else {
                                    ApiResult.Success(responseBodyToString(resp))
                                }
                            }
                        } catch (e: IOException) {
                            onFailure(call, e)
                            return
                        }
                        cont.resume(result)
                    }
                })
            }
        } finally {
            if (activeCall === call) {
                activeCall = null
            }
            SyncthingHttpClients.untrack(call)
        }
    }

    private fun newCall(requestMethod: String, uri: Uri?, requestBody: String?): Call? {
        val uriString = uri?.toString()
        if (uriString.isNullOrEmpty()) {
            return null
        }
        val requestBuilder = Request.Builder().url(uriString)
        apiKey?.let { requestBuilder.header(HEADER_API_KEY, it) }
        val body = requestBody?.toRequestBody(null)
            ?: ByteArray(0).toRequestBody(null)
        requestBuilder.method(requestMethod, if (requestMethod == "GET") null else body)
        return SyncthingHttpClients.track(
            SyncthingHttpClients.get(context).newCall(requestBuilder.build())
        )
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
         * Retries of a GET that hit a read/write timeout, see [enqueue]. Kept small so a
         * hung core still surfaces an error quickly.
         */
        private const val MAX_TIMEOUT_RETRIES = 2
        private const val TIMEOUT_RETRY_BACKOFF_MS = 100L

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
