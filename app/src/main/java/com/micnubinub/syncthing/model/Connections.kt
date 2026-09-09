package com.micnubinub.syncthing.model

data class Connections(
    @JvmField var total: Connection? = null,
    @JvmField var connections: MutableMap<String, Connection?>? = null
)
