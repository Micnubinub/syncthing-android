package com.micnubinub.syncthing.model

data class Defaults(
    var device: Device? = null,
    var folder: Folder? = null,
    var ignores: Ignores? = null
)
