package com.micnubinub.syncthing.model

data class RemoteIgnoredDevice(
    var time: String = "",
    var deviceID: String = "",
    var name: String = "",
    var address: String = ""
) {
    val displayName: String?
        /**
         * Returns the device name, or the first characters of the ID if the name is empty.
         */
        get() = if (name.isNullOrEmpty())
            deviceID.take(7)
        else
            name
}
