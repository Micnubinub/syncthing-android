package com.micnubinub.syncthing.model

/**
 * To avoid name confusion:
 * This is the exclude and include items list associated with every folder.
 */
data class FolderIgnoreList(
    var expanded: List<String>? = null,
    var ignore: List<String>? = null
)
