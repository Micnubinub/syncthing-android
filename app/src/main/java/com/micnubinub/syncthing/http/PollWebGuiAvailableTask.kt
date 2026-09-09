package com.micnubinub.syncthing.http

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.ConnectException
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PollWebGuiAvailableTask(
    context: Context?, url: URL, apiKey: String?,
    private val listener: OnSuccessListener?,
    parentJob: Job? = null
) : ApiRequest(context, url, "", apiKey) {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob(parentJob))
    private var pollingJob: Job? = null
    private var logIncidence = 0

    @Volatile
    private var cancelled = false
    private var currentBackoffMs = INITIAL_BACKOFF_MS

    init {
        Log.i(TAG, "Starting to poll for web gui availability")
        start(listener)
    }

    fun cancelRequestsAndCallback() {
        cancelled = true
        pollingJob?.cancel()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun start(listener: OnSuccessListener?) {
        pollingJob = scope.launch {
            while (isActive) {
                try {
                    val result = suspendPerformRequest()
                    if (!cancelled) {
                        Log.v(TAG, "Web GUI responded successfully")
                        listener?.onSuccess(result)
                    }
                    return@launch
                } catch (e: ConnectException) {
                    delay(currentBackoffMs)
                    currentBackoffMs = (currentBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                    logIncidence++
                    if (logIncidence == 1 || logIncidence % 10 == 0) {
                        Log.v(TAG, "Polling web gui ... ($logIncidence)")
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Unexpected error while polling web gui", e)
                    delay(currentBackoffMs)
                    currentBackoffMs = (currentBackoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                }
            }
        }
        pollingJob?.invokeOnCompletion {
            scope.coroutineContext[Job]?.cancel()
        }
    }

    private suspend fun suspendPerformRequest(): String {
        return suspendCancellableCoroutine { continuation ->
            val uri = buildUri(mutableMapOf())
            continuation.invokeOnCancellation {
                cancelRequest()
            }
            connect(
                "GET",
                uri,
                null,
                { result: String ->
                    if (continuation.isActive) {
                        continuation.resume(result)
                    }
                },
                { error: ApiError? ->
                    if (continuation.isActive) {
                        val cause: Throwable? = when (error) {
                            is ApiError.Network -> error.cause
                            is ApiError.Http -> null
                            is ApiError.Cancelled -> error.cause
                            null -> null
                        }
                        if (cause is ConnectException) {
                            continuation.resumeWithException(cause)
                        } else {
                            continuation.resumeWithException(
                                cause ?: Exception(
                                    (error as? ApiError.Http)?.let {
                                        "HTTP ${it.code}: ${it.message}"
                                    } ?: "Unknown error"
                                )
                            )
                        }
                    }
                })
        }
    }

    companion object {
        private const val INITIAL_BACKOFF_MS: Long = 150
        private const val MAX_BACKOFF_MS: Long = 10_000
    }
}
