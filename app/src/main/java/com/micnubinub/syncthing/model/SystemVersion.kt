package com.micnubinub.syncthing.model

data class SystemVersion(
    var arch: String? = null,
    var codename: String? = null,
    var longVersion: String? = null,
    var os: String? = null,
    var version: String? = null
)
