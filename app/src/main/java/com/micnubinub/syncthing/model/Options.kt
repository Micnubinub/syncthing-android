package com.micnubinub.syncthing.model

/**
 * Sources:
 * - https://github.com/syncthing/syncthing/tree/main/lib/config
 * - https://github.com/syncthing/syncthing/blob/main/lib/config/optionsconfiguration.go
 */
data class Options(
    var listenAddresses: List<String>? = null,
    var globalAnnounceServers: List<String>? = null,
    var globalAnnounceEnabled: Boolean = true,
    var localAnnounceEnabled: Boolean = true,
    var localAnnouncePort: Int = 21027,
    var localAnnounceMCAddr: String? = null, // [ff12::8384]:21027
    var maxSendKbps: Int = 0,
    var maxRecvKbps: Int = 0,
    var reconnectionIntervalS: Int = 60,
    var relaysEnabled: Boolean = true,
    var relayReconnectIntervalM: Int = 10,
    var startBrowser: Boolean = false,
    var natEnabled: Boolean = true,
    var natLeaseMinutes: Int = 60,
    var natRenewalMinutes: Int = 30,
    var natTimeoutSeconds: Int = 10,
    var urAccepted: Int = 0,
    var urUniqueId: String? = null,
    var urURL: String = "https://data.syncthing.net/newdata",
    var urPostInsecurely: Boolean = false,
    var urInitialDelayS: Int = 1800,
    var autoUpgradeIntervalH: Int = 0, // Normally "12" but makes no sense on Android.
    var upgradeToPreReleases: Boolean = false,
    var keepTemporariesH: Int = 24,
    var cacheIgnoredFiles: Boolean = false,
    var progressUpdateIntervalS: Int = 5,
    var limitBandwidthInLan: Boolean = false,
    var releasesURL: String = "https://upgrades.syncthing.net/meta.json",
    var alwaysLocalNets: List<String>? = null,
    var overwriteRemoteDeviceNamesOnConnect: Boolean = false,
    var tempIndexMinBlocks: Int = 10,
    var setLowPriority: Boolean = true,
    var minHomeDiskFree: MinHomeDiskFree? = null,
    var crURL: String = "https://crash.syncthing.net/newcrash",
    var crashReportingEnabled: Boolean = true,
    var stunKeepaliveStartS: Int = 180,
    var stunKeepaliveMinS: Int = 20,
    var stunServer: String = "default",
    var maxFolderConcurrency: Int = 1,
    var maxConcurrentIncomingRequestKiB: Int = 0,
    var announceLANAddresses: Boolean = true,
    var sendFullIndexOnUpgrade: Boolean = false,
    var featureFlag: String = "",
    var connectionLimitEnough: Int = 0,
    var connectionLimitMax: Int = 0,
    var unackedNotificationID: String = ""
) {
    data class MinHomeDiskFree(
        var value: Float = 1f,
        var unit: String = "%"
    )

    fun isUsageReportingAccepted(urVersionMax: Int): Boolean {
        return urAccepted == urVersionMax
    }

    fun isUsageReportingDecided(urVersionMax: Int): Boolean {
        return isUsageReportingAccepted(urVersionMax) || urAccepted == USAGE_REPORTING_DENIED
    }

    companion object {
        const val USAGE_REPORTING_UNDECIDED: Int = 0
        const val USAGE_REPORTING_DENIED: Int = -1
    }
}
