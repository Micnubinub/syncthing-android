package com.micnubinub.syncthing.http

import android.content.Context
import android.util.Log
import androidx.core.net.toUri
import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonParser.parseString
import com.micnubinub.syncthing.http.EventsPoller.Companion.POLL_TIMEOUT_SEC
import com.micnubinub.syncthing.http.EventsPoller.Companion.READ_TIMEOUT_SEC
import com.micnubinub.syncthing.model.Event
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Long-polls syncthing's `/rest/events` endpoint and delivers events as they occur.
 *
 * GET `/rest/events?since=&timeout=` blocks until new events arrive (or
 * [POLL_TIMEOUT_SEC] elapses) and returns a JSON array.
 *
 * The stream starts at the current end of the server's event buffer (`since=0` replays
 * the buffered tail once per binary run) and resumes from [lastEventId] after every
 * reconnect, so no events are lost across connection drops.
 *
 * Security model matches [ApiRequest]: the URL is pinned to the loopback interface
 * via [ApiRequest.forceLoopbackHost] and authenticated with the X-API-Key header,
 * reusing the shared TLS-pinned client from [SyncthingHttpClients].
 */
class EventsPoller internal constructor(
    private val context: Context,
    url: URL,
    apiKey: String?,
    private val onEvent: (event: Event, json: JsonElement?) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gson = GsonBuilder().create()
    private val apiKey: String? = apiKey

    /**
     * Loopback-pinned base URL; see [ApiRequest.forceLoopbackHost] for why this is
     * security-relevant and must not be bypassed.
     */
    private val baseUrl = ApiRequest.forceLoopbackHost(url)

    /**
     * Derived from the shared client so pool, dispatcher and SSL config stay shared.
     * Read timeout is longer than [POLL_TIMEOUT_SEC] so a quiet long-poll is not
     * aborted by the shared 30s timeout.
     */
    private val client: OkHttpClient by lazy {
        SyncthingHttpClients.get(context).newBuilder()
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var stopped = true

    @Volatile
    private var activeCall: Call? = null

    /**
     * Id of the last event received. Used as the `since` parameter when reconnecting
     * so the server resumes the stream where it left off.
     */
    @Volatile
    var lastEventId: Long = 0
        private set

    private var reconnectDelayMs = RECONNECT_DELAY_INITIAL_MS
    private var pollJob: Job? = null

    fun start() {
        if (!stopped) return
        stopped = false
        pollJob = scope.launch { pollLoop() }
    }

    fun stop() {
        stopped = true
        pollJob?.cancel()
        activeCall?.cancel()
        activeCall = null
        // This instance is never restarted (RestApi creates a fresh instance on each
        // start), so cancel the supervisor scope to drop any pending poll/reconnect jobs.
        scope.cancel()
    }

    private suspend fun pollLoop() {
        while (!stopped) {
            try {
                val body = fetchEvents()
                reconnectDelayMs = RECONNECT_DELAY_INITIAL_MS
                dispatchEvents(body)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (stopped) return
                Log.w(
                    TAG, "Event stream disconnected, retrying in $reconnectDelayMs ms",
                    e
                )
                delay(reconnectDelayMs)
                reconnectDelayMs = minOf(reconnectDelayMs * 2, RECONNECT_DELAY_MAX_MS)
            }
        }
    }

    private fun fetchEvents(): String {
        val request = Request.Builder().url(buildPollUri().toString())
            .apply { apiKey?.let { header(HEADER_API_KEY, it) } }
            .get()
            .build()
        val call = client.newCall(request)
        activeCall = call
        try {
            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("HTTP ${response.code}")
                }
                return response.body.string()
            }
        } finally {
            if (activeCall === call) {
                activeCall = null
            }
        }
    }

    private fun buildPollUri() = baseUrl.toString().toUri().buildUpon()
        .path(GetRequest.URI_EVENTS)
        .appendQueryParameter("since", lastEventId.toString())
        .appendQueryParameter("timeout", POLL_TIMEOUT_SEC.toString())
        .build()

    private fun dispatchEvents(body: String) {
        val json = try {
            parseString(body)
        } catch (ex: JsonParseException) {
            Log.w(TAG, "Skipping malformed events payload, raw=[$body]")
            return
        }
        if (!json.isJsonArray) {
            Log.w(TAG, "Events payload is not an array, raw=[$body]")
            return
        }
        for (element in json.asJsonArray) {
            if (stopped) return
            val event = try {
                gson.fromJson(element, Event::class.java)
            } catch (ex: JsonParseException) {
                Log.w(TAG, "Skipping event due to JsonParseException, raw=[$element]")
                continue
            }
            val id = event.id.toLong()
            if (id > lastEventId) {
                lastEventId = id
            }
            onEvent(event, element)
        }
    }

    companion object {
        private const val TAG = "EventsPoller"

        /**
         * The name of the HTTP header used for the syncthing API key.
         */
        private const val HEADER_API_KEY = "X-API-Key"

        private const val RECONNECT_DELAY_INITIAL_MS = 1000L
        private const val RECONNECT_DELAY_MAX_MS = 60_000L

        /**
         * Syncthing long-poll wait. Must stay below [READ_TIMEOUT_SEC] so a quiet
         * poll returns `[]` instead of tripping the HTTP client timeout.
         */
        private const val POLL_TIMEOUT_SEC = 60L

        private const val READ_TIMEOUT_SEC = 75L
    }
}
