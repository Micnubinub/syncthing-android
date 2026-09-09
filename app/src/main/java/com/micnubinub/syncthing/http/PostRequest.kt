package com.micnubinub.syncthing.http

class PostRequest(
    context: android.content.Context,
    url: java.net.URL,
    path: String?,
    apiKey: String?,
    params: MutableMap<String, String?>?,
    postBody: String?,
    listener: OnSuccessListener?,
    onError: OnErrorListener? = null
) : ApiRequest(context, url, path, apiKey) {
    init {
        val safeParams = com.google.common.base.Optional.fromNullable(params)
            .or(mutableMapOf())
        val uri = buildUri(safeParams)
        connect("POST", uri, postBody, listener, onError)
    }

    companion object {
        const val URI_DB_IGNORES: String = "/rest/db/ignores"
        const val URI_DB_OVERRIDE: String = "/rest/db/override"
        const val URI_DB_REVERT: String = "/rest/db/revert"
        const val URI_DB_SCAN: String = "/rest/db/scan"
        const val URI_SYSTEM_CONFIG: String = "/rest/system/config"
        const val URI_SYSTEM_SHUTDOWN: String = "/rest/system/shutdown"
    }
}
