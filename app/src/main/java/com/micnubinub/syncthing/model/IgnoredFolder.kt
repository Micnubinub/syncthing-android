package com.micnubinub.syncthing.model

data class IgnoredFolder(
    var id: String = "",
    var label: String = "",
    var time: String = ""
) {
    val displayLabel: String
        /**
         * Returns the folder label, or the first characters of the ID if the label is empty.
         */
        get() = if (label.isNullOrEmpty())
            id.take(7)
        else
            label
}
