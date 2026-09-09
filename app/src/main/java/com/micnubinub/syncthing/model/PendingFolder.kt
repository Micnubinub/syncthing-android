package com.micnubinub.syncthing.model

data class PendingFolder(
    var label: String = "",
    var time: String = "",
    var receiveEncrypted: Boolean = false,
    var remoteEncrypted: Boolean = false
)
