package com.micnubinub.syncthing.util

import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.CoroutineScope

val LocalActivityScope = staticCompositionLocalOf<CoroutineScope> {
    error("No activity scope provided")
}
