package com.micnubinub.syncthing.http

import android.content.Context
import com.google.common.base.Optional
import java.net.URL

/**
 * Performs a GET request to the Syncthing API
 */
class GetRequest(
    context: Context, url: URL, path: String?, apiKey: String?,
    params: MutableMap<String, String?>?,
    onSuccess: OnSuccessListener?, onError: OnErrorListener?
) : ApiRequest(context, url, path, apiKey) {
    init {
        val safeParams = Optional.fromNullable(params)
            .or(mutableMapOf())
        val uri = buildUri(safeParams)
        connect("GET", uri, null, onSuccess, onError)
    }

    companion object {
        const val URI_CONFIG: String = "/rest/system/config"
        const val URI_SYSTEM_DISCOVERY: String = "/rest/system/discovery"
        const val URI_SYSTEM_LOGLEVELS: String = "/rest/system/loglevels"
        const val URI_VERSION: String = "/rest/system/version"
        const val URI_SYSTEM_STATUS: String = "/rest/system/status"
        const val URI_CONNECTIONS: String = "/rest/system/connections"
        const val URI_PENDING_DEVICES: String = "/rest/cluster/pending/devices"
        const val URI_PENDING_FOLDERS: String = "/rest/cluster/pending/folders"
        const val URI_DEBUG_SUPPORT: String = "/rest/debug/support"
        const val URI_DB_COMPLETION: String = "/rest/db/completion"
        const val URI_DB_IGNORES: String = "/rest/db/ignores"
        const val URI_DB_STATUS: String = "/rest/db/status"
        const val URI_DEVICEID: String = "/rest/svc/deviceid"
        const val URI_REPORT: String = "/rest/svc/report"
        const val URI_EVENTS: String = "/rest/events"
        const val URI_EVENTS_DISK: String = "/rest/events/disk"
        const val URI_STATS_DEVICE: String = "/rest/stats/device"
    }
}
