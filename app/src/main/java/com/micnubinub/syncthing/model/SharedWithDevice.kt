package com.micnubinub.syncthing.model

import androidx.compose.runtime.Immutable

@Immutable
data class SharedWithDevice(
    var deviceID: String? = null,
    var introducedBy: String = "",
    var encryptionPassword: String = ""
) {
    val displayName: String?
        get() = (if (deviceID.isNullOrEmpty()) "" else deviceID?.take(7))
}
