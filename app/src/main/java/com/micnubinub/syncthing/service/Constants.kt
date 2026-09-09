package com.micnubinub.syncthing.service

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import java.io.File
import java.util.concurrent.TimeUnit

object Constants {
    // Always set ENABLE_TEST_DATA to false before building debug or release APK's.
    const val ENABLE_TEST_DATA: Boolean = false

    const val FILENAME_SYNCTHING_BINARY: String = "libsyncthingnative.so"
    const val FILENAME_STIGNORE: String = ".stignore"
    const val FILENAME_STFOLDER: String = ".stfolder"
    const val FOLDER_NAME_STVERSIONS: String = ".stversions"

    // Preferences - Run conditions
    const val PREF_RUN_ON_MOBILE_DATA: String = "run_on_mobile_data"
    const val PREF_RUN_ON_ROAMING: String = "run_on_roaming"
    const val PREF_RUN_ON_WIFI: String = "run_on_wifi"
    const val PREF_RUN_ON_METERED_WIFI: String = "run_on_metered_wifi"
    const val PREF_USE_WIFI_SSID_WHITELIST: String = "use_wifi_whitelist"
    const val PREF_WIFI_SSID_WHITELIST: String = "wifi_ssid_whitelist"
    const val PREF_POWER_SOURCE: String = "power_source"
    const val PREF_RESPECT_BATTERY_SAVING: String = "respect_battery_saving"
    const val PREF_RESPECT_MASTER_SYNC: String = "respect_master_sync"
    const val PREF_RUN_IN_FLIGHT_MODE: String = "run_in_flight_mode"
    const val PREF_RUN_ON_TIME_SCHEDULE: String = "run_on_time_schedule"
    const val PREF_SYNC_DURATION_MINUTES: String = "sync_duration_minutes"
    const val PREF_SLEEP_INTERVAL_MINUTES: String = "sleep_interval_minutes"

    // Preferences - User Interface
    const val PREF_APP_THEME: String = "app_theme"
    const val PREF_EXPERT_MODE: String = "expert_mode"
    const val PREF_START_INTO_WEB_GUI: String = "start_into_web_gui"

    // Preferences - Behaviour
    const val PREF_START_SERVICE_ON_BOOT: String = "always_run_in_background"
    const val PREF_BROADCAST_SERVICE_CONTROL: String = "broadcast_service_control"
    const val PREF_ALLOW_OVERWRITE_FILES: String = "allow_overwrite_files"

    // Preferences - Syncthing Options
    const val PREF_WEBUI_USERNAME: String = "webui_username"
    const val PREF_WEBUI_PASSWORD: String = "webui_password"

    // Preferences - Import and Export
    const val PREF_BACKUP_REL_PATH_TO_ZIP: String = "backup_rel_path_to_zip"
    const val PREF_BACKUP_PASSWORD: String = "backup_password"

    // Preferences - Troubleshooting
    const val PREF_VERBOSE_LOG: String = "verbose_log"

    // Dialog - Anonymous Usage Reporting
    const val PREF_USAGE_REPORTING_DIALOG_ANSWERED: String = "usage_reporting_dialog_answered"
    const val PREF_ENVIRONMENT_VARIABLES: String = "environment_variables"
    const val PREF_DEBUG_FACILITIES_ENABLED: String = "debug_facilities_enabled"

    // Preferences - Experimental
    const val PREF_USE_TOR: String = "use_tor"
    const val PREF_SOCKS_PROXY_ADDRESS: String = "socks_proxy_address"
    const val PREF_HTTP_PROXY_ADDRESS: String = "http_proxy_address"

    // Preferences - per Folder and Device Sync Conditions
    const val PREF_OBJECT_PREFIX_FOLDER: String = "sc_folder_"
    const val PREF_OBJECT_PREFIX_DEVICE: String = "sc_device_"

    // Preferences - Recent Changes screen
    const val PREF_SHOW_EXACT_TIMES: String = "recent_changes_show_exact_times"

    /**
     * Cached information which is not available on SettingsActivity.
     */
    const val PREF_ENABLE_SYNCTHING_CAMERA: String = "enableSyncthingCamera"
    const val PREF_KNOWN_WIFI_SSIDS: String = "knownWifiSsids"
    const val PREF_LAST_BINARY_VERSION: String = "lastBinaryVersion"
    const val PREF_LOCAL_DEVICE_ID: String = "localDeviceID"

    // from SystemClock.elapsedRealtime()
    const val PREF_LAST_RUN_TIME: String = "last_run_time"
    const val PREF_APP_START_COUNTER: String = "app_start_counter"

    /**
     * Persisted mapping of folderId -> original cleanupIntervalS while the
     * startup versioning cleanup hack is active, so the user's values can be
     * restored even if the process is killed in between.
     */
    const val PREF_VERSIONING_CLEANUP_RESTORE: String = "versioning_cleanup_restore"

    /**
     * Cached device stats.
     */
    const val PREF_CACHE_DEVICE_LASTSEEN_PREFIX: String = "device_lastseen_"

    /**
     * Per-install random token embedded in notification PendingIntents that
     * target MainActivity, so the exported activity only honors its special
     * actions/extras when the intent originated from this app.
     */
    const val PREF_INTERNAL_INTENT_TOKEN: String = "internal_intent_token"

    /**
     * Host + certificate error combinations for which the user accepted the
     * WebView SSL security notice, persisted so they survive process death.
     */
    const val PREF_ACCEPTED_SSL_EXCEPTIONS: String = "accepted_ssl_exceptions"

    /**
     * [ConfigXml.addSyncthingCameraFolder]
     */
    const val syncthingCameraFolderId: String = "syncthingAndroidCamera-52x89-60es4"

    /**
     * [RunConditionMonitor]
     * [StatusFragment]
     */
    const val PREF_BTNSTATE_FORCE_START_STOP: String = "btnStateForceStartStop"
    const val BTNSTATE_NO_FORCE_START_STOP: Int = 0
    const val BTNSTATE_FORCE_START: Int = 1
    const val BTNSTATE_FORCE_STOP: Int = 2

    /**
     * [EventProcessor]
     */
    const val PREF_EVENT_PROCESSOR_LAST_SYNC_ID: String = "last_sync_id"

    /**
     * Available options cache for preference [com.micnubinub.syncthing.R.xml.app_settings]
     * Read via REST API call in [RestApi.updateDebugFacilitiesCache] after first successful binary startup.
     */
    const val PREF_DEBUG_FACILITIES_AVAILABLE: String = "debug_facilities_available"

    /**
     * Available folder types.
     */
    const val FOLDER_TYPE_SEND_ONLY: String = "sendonly"
    const val FOLDER_TYPE_SEND_RECEIVE: String = "sendreceive"
    const val FOLDER_TYPE_RECEIVE_ONLY: String = "receiveonly"
    const val FOLDER_TYPE_RECEIVE_ENCRYPTED: String = "receiveencrypted"

    /**
     * Default listening ports.
     */
    const val DEFAULT_WEBGUI_TCP_PORT: Int = 8384
    const val DEFAULT_DATA_TCP_PORT: Int = 22000

    /**
     * Interval in ms at which RestAPI is polled.
     * As a rule of thumb: Poll faster on "modern" devices.
     */
    @JvmField
    val REST_UPDATE_INTERVAL: Long = TimeUnit.SECONDS.toMillis(
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            3
        else
            5).toLong()
    )
    val GUI_UPDATE_INTERVAL: Long = 1_500

    /**
     * File in the config folder that contains configuration.
     */
    const val CONFIG_FILE: String = "config.xml"

    /**
     * Name of the public key file in the data directory.
     */
    const val PUBLIC_KEY_FILE: String = "cert.pem"

    /**
     * Name of the private key file in the data directory.
     */
    const val PRIVATE_KEY_FILE: String = "key.pem"

    /**
     * Name of the public HTTPS CA file in the data directory.
     */
    const val HTTPS_CERT_FILE: String = "https-cert.pem"

    /**
     * Name of the HTTPS CA key file in the data directory.
     */
    const val HTTPS_KEY_FILE: String = "https-key.pem"

    /**
     * Name of the file holding the SharedPreferences backup.
     * Do not use getCacheDir() because the path to import will then be wrong as
     * zipFile.extractAll will write to getFilesDir().
     */
    const val SHARED_PREFS_FILE: String = "sharedpreferences.dat"

    /**
     * File in the config folder we write to temporarily before renaming to CONFIG_FILE.
     */
    private const val CONFIG_TEMP_FILE = "config.xml.tmp"

    /**
     * Name of the folder containing the index database.
     */
    private const val INDEX_DB_FOLDER = "index-v2"

    @JvmStatic
    fun DYN_PREF_OBJECT_CUSTOM_SYNC_CONDITIONS(objectPrefixAndId: String?): String {
        return objectPrefixAndId + "_custom_sync_conditions"
    }

    @JvmStatic
    fun DYN_PREF_OBJECT_FOLDER_RUN_SCRIPT(folderId: String?): String {
        return PREF_OBJECT_PREFIX_FOLDER + folderId + "_run_script"
    }

    @JvmStatic
    fun DYN_PREF_OBJECT_SYNC_ON_WIFI(objectPrefixAndId: String?): String {
        return objectPrefixAndId + "_" + PREF_RUN_ON_WIFI
    }

    @JvmStatic
    fun DYN_PREF_OBJECT_USE_WIFI_SSID_WHITELIST(objectPrefixAndId: String?): String {
        return objectPrefixAndId + "_" + PREF_USE_WIFI_SSID_WHITELIST
    }

    @JvmStatic
    fun DYN_PREF_OBJECT_SELECTED_WHITELIST_SSID(objectPrefixAndId: String?): String {
        return objectPrefixAndId + "_" + PREF_WIFI_SSID_WHITELIST
    }

    @JvmStatic
    fun DYN_PREF_OBJECT_SYNC_ON_METERED_WIFI(objectPrefixAndId: String?): String {
        return objectPrefixAndId + "_" + PREF_RUN_ON_METERED_WIFI
    }

    @JvmStatic
    fun DYN_PREF_OBJECT_SYNC_ON_MOBILE_DATA(objectPrefixAndId: String?): String {
        return objectPrefixAndId + "_" + PREF_RUN_ON_MOBILE_DATA
    }

    @JvmStatic
    fun DYN_PREF_OBJECT_SYNC_ON_POWER_SOURCE(objectPrefixAndId: String?): String {
        return objectPrefixAndId + "_" + PREF_POWER_SOURCE
    }

    @JvmStatic
    fun getConfigFile(context: Context): File {
        return File(context.filesDir, CONFIG_FILE)
    }

    fun getConfigTempFile(context: Context): File {
        return File(context.filesDir, CONFIG_TEMP_FILE)
    }

    @JvmStatic
    fun getPublicKeyFile(context: Context): File {
        return File(context.filesDir, PUBLIC_KEY_FILE)
    }

    @JvmStatic
    fun getPrivateKeyFile(context: Context): File {
        return File(context.filesDir, PRIVATE_KEY_FILE)
    }

    @JvmStatic
    fun getIndexDbFolder(context: Context): File {
        return File(context.filesDir, INDEX_DB_FOLDER)
    }

    @JvmStatic
    fun getHttpsCertFile(context: Context?): File {
        return File(context?.filesDir, HTTPS_CERT_FILE)
    }

    @JvmStatic
    fun getHttpsKeyFile(context: Context): File {
        return File(context.filesDir, HTTPS_KEY_FILE)
    }

    @JvmStatic
    fun getSharedPrefsFile(context: Context): File {
        return File(context.filesDir, SHARED_PREFS_FILE)
    }

    /**
     * Get libsyncthingnative.so absolute path and filename.
     */
    @JvmStatic
    fun getSyncthingBinary(context: Context): File {
        return File(context.applicationInfo.nativeLibraryDir, FILENAME_SYNCTHING_BINARY)
    }

    /**
     * Log file storage locations.
     */
    fun getAndroidLogFile(context: Context): File {
        // e.g. /data/data/${applicationId}/cache/android.log
        return File(context.cacheDir, "android.log")
    }

    @JvmStatic
    fun getSyncthingLogFile(context: Context): File {
        // e.g. /data/data/${applicationId}/files/syncthing.log
        return File(context.filesDir, "syncthing.log")
    }

    @JvmStatic
    val isRunningOnEmulator: Boolean
        /**
         * Checks if the app is running on an Android emulator (AVD).
         */
        get() = !Build.MANUFACTURER.isNullOrEmpty() && !Build.MODEL.isNullOrEmpty() &&
                (Build.MANUFACTURER == "Google" || Build.MANUFACTURER == "unknown") &&
                (Build.MODEL == "Android SDK built for x86" ||
                        Build.MODEL == "Android SDK built for x86_64" ||
                        Build.MODEL == "sdk_gphone_x86_arm")

    fun isDebuggable(context: Context): Boolean {
        return (0 != (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE))
    }

    /**
     * Detect kernels with a bug causing kernel oops when
     * Syncthing v1.3.0+ attempts to enable the NAT feature.
     */
    fun osHasKernelBugIssue505(): Boolean {
        val kernelVersion = System.getProperty("os.version")
        if (kernelVersion == null) {
            return false
        }
        /**
         * Affected kernels:
         * Samsung Note N7000 - LOS 16 - Android 9 - 3.0.101-gf32669ee5be #1 Tue Apr 7 20:05:58 +08 2020
         */
        return kernelVersion.startsWith("3.0.") ||
                kernelVersion.startsWith("3.4.")
    }

    object PowerSource {
        const val CHARGER_BATTERY: String = "ac_and_battery_power"
        const val CHARGER: String = "ac_power"
        const val BATTERY: String = "battery_power"
    }
}
