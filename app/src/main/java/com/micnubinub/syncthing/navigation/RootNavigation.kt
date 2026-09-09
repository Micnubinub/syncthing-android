package com.micnubinub.syncthing.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSerializable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.runtime.serialization.NavKeySerializer
import com.micnubinub.syncthing.service.SyncthingService
import kotlinx.serialization.Serializable

/**
 * The set of screens reachable from the root back stack. Every screen of the
 * app (except the tab contents of the main screen) is a destination here.
 */
@Serializable
sealed interface RootRoute : NavKey {
    @Serializable
    data object Onboarding : RootRoute

    @Serializable
    data object Main : RootRoute

    @Serializable
    data class Settings(val startDestination: String? = null) : RootRoute

    @Serializable
    data object RecentChanges : RootRoute

    @Serializable
    data object WebGui : RootRoute

    @Serializable
    data class WebView(val url: String) : RootRoute

    @Serializable
    data object Log : RootRoute

    @Serializable
    data class Device(
        val isCreate: Boolean,
        val deviceId: String? = null,
        val deviceName: String? = null,
        val notificationId: Int = 0,
    ) : RootRoute

    @Serializable
    data class Folder(
        val isCreate: Boolean,
        val folderId: String? = null,
        val folderLabel: String? = null,
        val shareWithDeviceId: String? = null,
        val receiveEncrypted: Boolean = false,
        val notificationId: Int = 0,
    ) : RootRoute

    @Serializable
    data class FolderPicker(
        val initialDirectory: String? = null,
        val rootDirectory: String? = null,
    ) : RootRoute

    @Serializable
    data class SyncConditions(
        val objectPrefixAndId: String,
        val readableName: String? = null,
    ) : RootRoute

    @Serializable
    data class FolderTypeDialog(val currentType: String? = null) : RootRoute

    @Serializable
    data class PullOrderDialog(val currentOrder: String? = null) : RootRoute

    @Serializable
    data class VersioningDialog(
        val type: String? = null,
        val params: Map<String, String> = emptyMap(),
    ) : RootRoute

    @Serializable
    data object QRScanner : RootRoute
}

/**
 * Pops [route] and every route above it from the back stack and hands [result]
 * to the caller that navigated to [route] (if any). The caller stays on the
 * back stack and is recomposed with the result.
 */
data class VersioningResult(val type: String, val params: Map<String, String>)

interface RootNavigator {
    val backStack: NavBackStack<RootRoute>

    /** Pushes [route]; optionally registers [onResult] to be called when the route delivers a result. */
    fun navigateTo(route: RootRoute, onResult: ((Any?) -> Unit)? = null)

    /** Pops the top route; if the back stack has a single entry, exits the app. */
    fun navigateBack()

    fun navigateUp()

    /** Clears the back stack and replaces it with [routes]. */
    fun resetTo(routes: List<RootRoute>)

    /** Pops the back stack down to and including [route] and delivers [result] to its caller. */
    fun deliverResult(route: RootRoute, result: Any?)
}

@Composable
fun rememberRootNavBackStack(initialRoutes: List<RootRoute>): NavBackStack<RootRoute> {
    return rememberSerializable(
        serializer = NavBackStackSerializer(elementSerializer = NavKeySerializer()),
    ) {
        NavBackStack(*initialRoutes.toTypedArray())
    }
}

@Composable
fun rememberRootNavigator(
    backStack: NavBackStack<RootRoute>,
    onExit: (RootRoute) -> Unit,
): RootNavigator {
    val pendingResults = remember { mutableMapOf<RootRoute, (Any?) -> Unit>() }
    return remember(backStack, onExit) {
        object : RootNavigator {
            override val backStack: NavBackStack<RootRoute> = backStack

            override fun navigateTo(route: RootRoute, onResult: ((Any?) -> Unit)?) {
                onResult?.let {
                    pendingResults[route] = onResult
                }
                backStack.add(route)
            }

            override fun navigateBack() {
                if (backStack.size > 1) {
                    val popped = backStack.removeAt(backStack.lastIndex)
                    pendingResults.remove(popped)
                } else {
                    onExit(backStack.last())
                }
            }

            override fun navigateUp() = navigateBack()

            override fun resetTo(routes: List<RootRoute>) {
                pendingResults.clear()
                backStack.clear()
                backStack.addAll(routes)
            }

            override fun deliverResult(route: RootRoute, result: Any?) {
                if (route !in backStack) {
                    pendingResults.remove(route)
                    return
                }
                val callback = pendingResults.remove(route)
                while (backStack.isNotEmpty()) {
                    if (backStack.removeAt(backStack.lastIndex) == route) {
                        break
                    }
                }
                callback?.invoke(result)
            }
        }
    }
}

val LocalRootNavigator = staticCompositionLocalOf<RootNavigator> {
    error("RootNavigator not provided")
}

val LocalSyncthingService = staticCompositionLocalOf<SyncthingService?> { null }

val LocalServiceUpdateTick = staticCompositionLocalOf { 0 }
