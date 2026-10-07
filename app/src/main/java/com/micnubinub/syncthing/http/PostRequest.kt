package com.micnubinub.syncthing.http

import android.content.Context
import com.google.common.base.Optional
import java.net.URL

class PostRequest(
    context: Context,
    url: URL,
    path: String?,
    apiKey: String?,
    params: MutableMap<String, String?>?,
    postBody: String?,
    listener: OnSuccessListener?,
    onError: OnErrorListener? = null
) : ApiRequest(context, url, path, apiKey) {
    init {
        connect("POST", buildUri(safeParams(params)), postBody, listener, onError)
    }

    companion object {
        const val URI_DB_IGNORES: String = "/rest/db/ignores"
        const val URI_DB_OVERRIDE: String = "/rest/db/override"
        const val URI_DB_REVERT: String = "/rest/db/revert"
        const val URI_DB_SCAN: String = "/rest/db/scan"
        const val URI_SYSTEM_CONFIG: String = "/rest/system/config"
        const val URI_SYSTEM_SHUTDOWN: String = "/rest/system/shutdown"

        /**
         * Sends a POST and suspends until Syncthing has accepted it.
         *
         * Callers that acknowledge a change to the user must use this rather than the
         * constructor: a fire-and-forget POST cannot tell them whether the write landed.
         *
         * @return the authoritative outcome of the request.
         */
        suspend fun postAndAwait(
            context: Context,
            url: URL,
            path: String?,
            apiKey: String?,
            params: MutableMap<String, String?>? = null,
            postBody: String? = null
        ): ApiResult = AwaitedPostRequest(
            context, url, path, apiKey, safeParams(params), postBody
        ).send()

        internal fun safeParams(params: MutableMap<String, String?>?): MutableMap<String, String?> =
            Optional.fromNullable(params).or(mutableMapOf())
    }
}

/**
 * A POST that is only ever awaited, so nothing is enqueued in a constructor and no
 * completion callback can be dropped on the floor.
 */
private class AwaitedPostRequest(
    context: Context,
    url: URL,
    path: String?,
    apiKey: String?,
    private val params: MutableMap<String, String?>,
    private val postBody: String?
) : ApiRequest(context, url, path, apiKey) {
    suspend fun send(): ApiResult = try {
        connectAwait("POST", buildUri(params), postBody)
    } finally {
        // This instance never uses the callback scope, but still owns one.
        cancelRequest()
    }
}
