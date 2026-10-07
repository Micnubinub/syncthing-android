package com.micnubinub.syncthing.ui.viewModels

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Stable
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.micnubinub.syncthing.model.Connection
import com.micnubinub.syncthing.model.SystemStatus
import com.micnubinub.syncthing.service.Constants.PREF_BTNSTATE_FORCE_START_STOP
import com.micnubinub.syncthing.service.Constants.REST_UPDATE_INTERVAL
import com.micnubinub.syncthing.service.RestApi
import com.micnubinub.syncthing.service.RunConditionBus
import com.micnubinub.syncthing.service.RunConditionEvent
import com.micnubinub.syncthing.service.SyncthingService
import com.micnubinub.syncthing.util.Util
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds

@Stable
data class StatusFragmentState(
    val isLoading: Boolean = false,
    val status: String = "",
    val ramUsage: String = "",
    val download: String = "",
    val upload: String = "",
    val announceServer: String = "",
    val uptime: String = "",
    val runReason: String = ""
)

sealed interface StatusFragmentAction {
    data object LoadData : StatusFragmentAction
    data object StopPolling : StatusFragmentAction
}

class StatusActivityViewModel(application: Application) : AndroidViewModel(application) {
    private val _state = MutableStateFlow(StatusFragmentState())
    val state: StateFlow<StatusFragmentState> = _state.asStateFlow()
    private var pollingJob: Job? = null

    private val _service = MutableStateFlow<SyncthingService?>(null)
    val service: StateFlow<SyncthingService?> = _service.asStateFlow()

    private val api: RestApi?
        get() = _service.value?.api

    private val preferences: SharedPreferences?
        get() = _service.value?.preferences

    fun setService(service: SyncthingService?) {
        _service.value = service
    }

    fun onAction(action: StatusFragmentAction) {
        when (action) {
            StatusFragmentAction.LoadData -> loadData()
            StatusFragmentAction.StopPolling -> stopPolling()
        }
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    private fun loadData() {
        try {
            pollingJob?.cancel()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Ignore; cancelling the previous polling job is best-effort.
        }
        val context = getApplication<Application>()
        pollingJob = viewModelScope.launch {
            api?.getRemoteDeviceStatus(null)
            while (true) {
                api?.getSystemStatus(listener = { systemStatus ->
                    onReceiveSystemStatus(context, systemStatus)
                })
                delay(REST_UPDATE_INTERVAL.milliseconds)
            }
        }
    }

    /**
     * Populates status holders with status received via [RestApi.getSystemStatus].
     */
    fun onReceiveSystemStatus(context: Context, systemStatus: SystemStatus) {
        val announceTotal = systemStatus.discoveryMethods
        val announceConnected = announceTotal - (systemStatus.discoveryErrors?.size ?: 0)

        var total: Connection? = null
        api?.let { restApi ->
            if (restApi.isConfigLoaded) {
                total = restApi.totalConnectionStatistic
            }
        }

        _state.update {
            it.copy(
                ramUsage = Util.readableFileSize(context, systemStatus.sys.toDouble()),
                announceServer = if (announceTotal == 0) "" else String.format(
                    Locale.getDefault(),
                    "%1\$d/%2\$d",
                    announceConnected,
                    announceTotal
                ),
                uptime = formatUptime(systemStatus.uptime),
                download = formatTransfer(
                    context,
                    total?.inBits ?: 0,
                    total?.inBytesTotal?.toDouble() ?: 0.0
                ),
                upload = formatTransfer(
                    context,
                    total?.outBits ?: 0,
                    total?.outBytesTotal?.toDouble() ?: 0.0
                ),
                runReason = _service.value?.runDecisionExplanation.orEmpty()
            )
        }
    }

    fun onRunConditionSelected(index: Int) {
        preferences?.let {
            it.edit {
                putInt(PREF_BTNSTATE_FORCE_START_STOP, index)
            }
        }
        RunConditionBus.tryEmit(RunConditionEvent.UpdateShouldRunDecision)
    }

    /**
     * Calculate readable uptime.
     */
    private fun formatUptime(uptimeSeconds: Long): String {
        val uptimeDays = TimeUnit.SECONDS.toDays(uptimeSeconds)
        val uptimeHours =
            TimeUnit.SECONDS.toHours(uptimeSeconds) - TimeUnit.DAYS.toHours(uptimeDays)
        val uptimeMinutes =
            TimeUnit.SECONDS.toMinutes(uptimeSeconds) - TimeUnit.HOURS.toMinutes(uptimeHours) - TimeUnit.DAYS.toMinutes(
                uptimeDays
            )
        return if (uptimeDays > 0) {
            String.format(
                Locale.getDefault(),
                "%dd %02dh %02dm",
                uptimeDays,
                uptimeHours,
                uptimeMinutes
            )
        } else if (uptimeHours > 0) {
            String.format(Locale.getDefault(), "%dh %02dm", uptimeHours, uptimeMinutes)
        } else {
            String.format(Locale.getDefault(), "%dm", uptimeMinutes)
        }
    }

    /**
     * "Hide" rates on the UI if they are lower than 1 KByte/sec. We don't like to
     * bother the user looking at discovery or index exchange traffic.
     */
    private fun formatTransfer(context: Context, bits: Long, bytesTotal: Double): String {
        val rate = if (bits / 8 < 1024) "0 B/s" else Util.readableTransferRate(context, bits)
        return rate + " (" + Util.readableFileSize(context, bytesTotal) + ")"
    }
}
