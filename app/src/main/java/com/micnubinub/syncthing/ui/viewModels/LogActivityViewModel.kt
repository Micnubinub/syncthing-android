package com.micnubinub.syncthing.ui.viewModels

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micnubinub.syncthing.service.Constants
import com.micnubinub.syncthing.util.Util
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset

@Stable
data class LogActivityState(
    val isLoading: Boolean = false,
    val androidLog: String = "",
    val syncthingLog: String = "",
    val showSyncthingLog: Boolean = false,
    val logFileToShare: File? = null
)

sealed interface LogActivityAction {
    data object LoadData : LogActivityAction
    data object ToggleShowSyncthingLog : LogActivityAction
    data object ShareLogFile : LogActivityAction
}

class LogActivityViewModel : ViewModel() {
    private val _state = MutableStateFlow(LogActivityState())
    val state: StateFlow<LogActivityState> = _state.asStateFlow()

    private var context: Context? = null
    private var fetchLogJob: Job? = null

    fun setContext(context: Context?) {
        this.context = context
    }

    fun setShowSyncthingLog(showSyncthingLog: Boolean) {
        _state.update { it.copy(showSyncthingLog = showSyncthingLog) }
    }

    fun onAction(action: LogActivityAction) {
        when (action) {
            LogActivityAction.LoadData -> loadData()
            LogActivityAction.ToggleShowSyncthingLog -> toggleShowSyncthingLog()
            LogActivityAction.ShareLogFile -> shareLogFile()
        }
    }

    private fun toggleShowSyncthingLog() {
        _state.update { it.copy(showSyncthingLog = !it.showSyncthingLog) }
        loadData()
    }

    private fun shareLogFile() {
        val context = this.context ?: return
        val logFile = if (_state.value.showSyncthingLog) {
            Constants.getSyncthingLogFile(context)
        } else {
            Constants.getAndroidLogFile(context)
        }
        _state.update { it.copy(logFileToShare = logFile) }
    }

    private fun loadData() {
        val context = this.context ?: return
        fetchLogJob?.cancel()
        fetchLogJob = viewModelScope.launch {
            _state.update { it.copy(isLoading = true) }
            val (androidLog, syncthingLog) = withContext(Dispatchers.IO) {
                val android = getAndroidLog()
                writeLogFile(Constants.getAndroidLogFile(context), android)
                val syncthing = readLogFile(Constants.getSyncthingLogFile(context))
                Pair(android, syncthing)
            }
            _state.update {
                it.copy(
                    isLoading = false,
                    androidLog = androidLog,
                    syncthingLog = syncthingLog
                )
            }
        }
    }

    private suspend fun getAndroidLog(): String {
        val output = Util.getCommandOutput(
            listOf(
                "/system/bin/logcat",
                "-t",
                ANDROID_LOG_FILE_MAX_LINES.toString(),
                "-v",
                "time",
                "*:i",
                "ps:s",
                "art:s"
            )
        )

        if (output.isEmpty()) {
            return ""
        }

        val result = StringBuilder(output.length)

        output.lineSequence().forEach { line ->
            if (line.contains("I/SyncthingNativeCode")) {
                return@forEach
            }

            if (ANDROID_LOG_IGNORE.any(line::contains)) {
                return@forEach
            }

            var start = 0

            // Remove date: MM-DD<space>
            if (line.length >= 6 &&
                line[2] == '-' &&
                line[5] == ' '
            ) {
                start = 6
            }

            // Remove milliseconds: HH:MM:SS.mmm<space>
            if (line.length >= start + 13 &&
                line[start + 2] == ':' &&
                line[start + 5] == ':' &&
                line[start + 8] == '.' &&
                line[start + 12] == ' '
            ) {
                start += 12
            }

            // Remove trailing newline except between retained lines.
            if (result.isNotEmpty()) {
                result.append('\n')
            }

            result.append(line, start, line.length)
        }

        return result.toString()
    }

    /**
     * Read or write log file.
     */
    private fun readLogFile(file: File): String {
        var content = ""
        try {
            if (file.exists()) {
                // readBytes() reads the whole file, handling partial reads and large sizes.
                content = file.readBytes().toString(Charset.forName("UTF-8"))
            } else {
                // File not found.
                Log.e(TAG, "readLogFile: File missing '$file'")
            }
        } catch (e: IOException) {
            Log.e(TAG, "readLogFile: Failed to read '$file' #1", e)
        }
        return content
    }

    private fun writeLogFile(file: File, logContent: String) {
        try {
            if (!file.exists()) {
                file.createNewFile()
            }
            FileOutputStream(file).use { fileOutputStream ->
                fileOutputStream.write(logContent.toByteArray(Charset.forName("UTF-8")))
            }
        } catch (e: IOException) {
            Log.w(TAG, "writeLogFile: Failed to write '$file' #1", e)
        }
    }

    companion object {
        private const val TAG = "LogActivityViewModel"
        private const val ANDROID_LOG_FILE_MAX_LINES = 2000
        private val ANDROID_LOG_IGNORE = arrayOf(
            "--- beginning of ",
            "/AbsListViewStubImpl",
            "W/ActionBarDrawerToggle",
            "/ActivityThread",
            "/Adreno",
            "/AdrenoUtils",
            "/AiRecognition",
            "/androidtc",
            "/AssistStructure",
            "/AudioCapabilities",
            "/BLASTBufferQueue_Java",
            "/BinderMonitor",
            "I/chatty",
            "/CameraExtImplXiaoMi",
            "/CameraManagerGlobal",
            "/Choreographer",
            "W/chmod",
            "/chromium",
            "/ContentCaptureHelper",
            "/ContentCatcher",
            "/cr_AwContents",
            "/cr_base",
            "/cr_BrowserStartup",
            "/cr_CachingUmaRecorder",
            "/cr_ChildProcessConn",
            "/cr_ChildProcLH",
            "/cr_CombinedPProvider",
            "/cr_CrashFileManager",
            "/cr_LibraryLoader",
            "/cr_media",
            "/cr_MediaCodecUtil",
            "/cr_PolicyProvider",
            "/cr_VAUtil",
            "/cr_WVCFactoryProvider",
            "I/ConfigStore",
            "/dalvikvm",
            "/DecorView",
            "/DpmTcmClient",
            "/EditorStubImpl",
            "/EGL",
            "/EpFrameworkFactory",
            "/eglCodecCommon",
            "/FeatureParser",
            "/FenceTime",
            "E/FileUtils err",
            "/FloatingActionMode",
            "/ForceDarkHelperStubImpl",
            "/FrameEvents",
            "/FramePredict",
            "/FrameTracker",
            "/Gralloc",
            "/HandWritingStubImpl",
            "/HWUI",
            "/IInputConnectionWrapper",
            "/ImeTracker",
            "/ImeBackDispatcher",
            "/ImeFocusController",
            "/InputEventReceiver",
            "/InputMethodManager",
            "/InsetsController",
            "/InsetsSourceConsumer",
            "/JavaheapMonitor",
            "E/LB",
            "W/Looper",
            "/libEGL",
            "/libMiGL",
            "E/libc",
            "W/libc",
            "/MessageMonitor",
            "/MIUI",
            "/MiInputConsumer",
            "/Miui",
            "MiuiBoosterUtils",
            "/NativeTurboSchedManager",
            "W/netstat",
            "/ngandroid.debu",
            "/OpenGLRenderer",
            "/PacProxySelector",
            "/Perf",
            "/re-initialized",
            "/RemoteInputConnectionImpl",
            "/RenderInspector",
            "/RenderThread",
            "/ResourceType",
            "W/sh",
            "W/Settings",
            "/SplineOverScroller",
            "/StrictMode",
            "/studio.deploy",
            "/SurfaceComposerClient",
            "/SurfaceSyncGroup",
            "I/System.out",
            "W/TextView",
            "I/Timeline",
            "I/VRI",
            "/VideoCapabilities",
            "/ViewRootImpl",
            "I/WebViewFactory",
            "WindowOnBackDispatcher",
            "I/X509Util",
            "/ziparchive",
            "/zygote",
            "/zygote64"
        )
    }
}
