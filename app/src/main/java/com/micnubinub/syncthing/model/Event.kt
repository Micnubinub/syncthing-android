package com.micnubinub.syncthing.model

data class Event(
    var id: Int = 0,
    var globalID: Int = 0,
    var type: String? = null,
    var time: String? = null,
    var data: MutableMap<String, Any>? = null
)
