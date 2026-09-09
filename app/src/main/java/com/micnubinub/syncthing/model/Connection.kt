package com.micnubinub.syncthing.model

import androidx.compose.runtime.Immutable
import kotlin.math.max

@Immutable
data class Connection(
    var address: String = "",
    var at: String = "", // "0001-01-01T00:00:00Z"
    var clientVersion: String = "",
    var connected: Boolean = false,
    var inBytesTotal: Long = 0,
    var outBytesTotal: Long = 0,
    var paused: Boolean = false,
    var type: String = "",
    // These fields are not sent from Syncthing. They are populated by {@link #setTransferRate}.
    var inBits: Long = 0,
    var outBits: Long = 0
) {
    fun setTransferRate(previous: Connection, msElapsed: Long) {
        val secondsElapsed = msElapsed / 1000
        val inBytes = 8 * (inBytesTotal - previous.inBytesTotal) / secondsElapsed
        val outBytes = 8 * (outBytesTotal - previous.outBytesTotal) / secondsElapsed
        inBits = max(0, inBytes)
        outBits = max(0, outBytes)
    }
}
