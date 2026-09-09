package com.micnubinub.syncthing.navigation

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.net.http.SslCertificate
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.util.Base64
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.ui.NavDisplay
import androidx.preference.PreferenceManager
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.model.DiskEvent
import com.micnubinub.syncthing.onboarding.OnboardingPage
import com.micnubinub.syncthing.onboarding.OnboardingScreen
import com.micnubinub.syncthing.onboarding.OnboardingUiState
import com.micnubinub.syncthing.service.Constants
import com.micnubinub.syncthing.service.SyncthingService
import com.micnubinub.syncthing.settings.LocalSettingsNavigator
import com.micnubinub.syncthing.settings.Navigator
import com.micnubinub.syncthing.settings.SettingsNavDisplay
import com.micnubinub.syncthing.settings.SettingsRoute
import com.micnubinub.syncthing.settings.createPreferenceFlow
import com.micnubinub.syncthing.settings.rememberSettingsNavBackStack
import com.micnubinub.syncthing.ui.screens.DeviceActivityScreen
import com.micnubinub.syncthing.ui.screens.FolderActivityScreen
import com.micnubinub.syncthing.ui.screens.FolderPickerActivityScreen
import com.micnubinub.syncthing.ui.screens.FolderTypeDialogActivityScreen
import com.micnubinub.syncthing.ui.screens.LogActivityScreen
import com.micnubinub.syncthing.ui.screens.MainActivityScreen
import com.micnubinub.syncthing.ui.screens.PullOrderDialogActivityScreen
import com.micnubinub.syncthing.ui.screens.QRScannerActivityScreen
import com.micnubinub.syncthing.ui.screens.RecentChangesActivityScreen
import com.micnubinub.syncthing.ui.screens.SyncConditionsActivityScreen
import com.micnubinub.syncthing.ui.screens.VersioningDialogActivityScreen
import com.micnubinub.syncthing.ui.screens.WebViewActivityScreen
import com.micnubinub.syncthing.ui.viewModels.DeviceActivityAction
import com.micnubinub.syncthing.ui.viewModels.DeviceActivityViewModel
import com.micnubinub.syncthing.ui.viewModels.FolderActivityAction
import com.micnubinub.syncthing.ui.viewModels.FolderActivityViewModel
import com.micnubinub.syncthing.ui.viewModels.FolderPickerActivityAction
import com.micnubinub.syncthing.ui.viewModels.FolderPickerActivityViewModel
import com.micnubinub.syncthing.ui.viewModels.FolderTypeDialogActivityViewModel
import com.micnubinub.syncthing.ui.viewModels.LogActivityAction
import com.micnubinub.syncthing.ui.viewModels.LogActivityViewModel
import com.micnubinub.syncthing.ui.viewModels.MainActivityViewModel
import com.micnubinub.syncthing.ui.viewModels.PullOrderDialogActivityViewModel
import com.micnubinub.syncthing.ui.viewModels.QRScannerActivityViewModel
import com.micnubinub.syncthing.ui.viewModels.RecentChangesActivityViewModel
import com.micnubinub.syncthing.ui.viewModels.SyncConditionsActivityAction
import com.micnubinub.syncthing.ui.viewModels.SyncConditionsActivityViewModel
import com.micnubinub.syncthing.ui.viewModels.VersioningDialogActivityViewModel
import com.micnubinub.syncthing.ui.viewModels.WebViewActivityAction
import com.micnubinub.syncthing.ui.viewModels.WebViewActivityViewModel
import com.micnubinub.syncthing.util.ConfigRouter
import com.micnubinub.syncthing.util.ConfigXml
import com.micnubinub.syncthing.util.FileUtils
import com.micnubinub.syncthing.util.LocalActivityScope
import com.micnubinub.syncthing.util.PermissionUtil
import com.micnubinub.syncthing.util.Util
import com.micnubinub.syncthing.webgui.WebGuiScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.zhanghai.compose.preference.Preferences
import me.zhanghai.compose.preference.ProvidePreferenceLocals
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.net.URL
import java.nio.charset.Charset
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.UUID
import androidx.lifecycle.compose.LocalLifecycleOwner as LocalLifecycleOwnerX

private const val TAG = "RootDestinations"

/**
 * Hosts the single root back stack of the app. Provides the [LocalRootNavigator] and preference
 * locals used by every destination below and renders the active destination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootNavDisplay(
    backStack: NavBackStack<RootRoute>,
    navigator: RootNavigator,
    preferences: SharedPreferences,
    mainViewModel: MainActivityViewModel,
    configRouter: ConfigRouter,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwnerX.current
    val activityScope = remember(lifecycleOwner) { lifecycleOwner.lifecycleScope }
    val prefFlow = remember(preferences, activityScope) {
        createPreferenceFlow(preferences, activityScope)
    }

    CompositionLocalProvider(
        LocalRootNavigator provides navigator,
        LocalActivityScope provides activityScope,
    ) {
        // The preference library's ProvidePreferenceLocals requires a
        // MutableStateFlow to write edits back. The flow is created mutable in
        // createPreferenceFlow but exposed as an immutable StateFlow everywhere
        // else, so only this root provider can replace its value.
        @Suppress("UNCHECKED_CAST")
        ProvidePreferenceLocals(flow = prefFlow as MutableStateFlow<Preferences>) {
            NavDisplay(
                backStack = backStack,
                onBack = { navigator.navigateBack() },
                entryProvider = entryProvider {
                    entry<RootRoute.Onboarding> { OnboardingDestination(preferences) }
                    entry<RootRoute.Main> {
                        MainDestination(
                            mainViewModel,
                            configRouter,
                            preferences
                        )
                    }
                    entry<RootRoute.Settings> {
                        SettingsDestination(it.startDestination)
                    }
                    entry<RootRoute.RecentChanges> { RecentChangesDestination(preferences) }
                    entry<RootRoute.WebGui> { WebGuiDestination() }
                    entry<RootRoute.WebView> { WebViewDestination(it.url) }
                    entry<RootRoute.Log> { LogDestination() }
                    entry<RootRoute.Device> { DeviceDestination(it, preferences) }
                    entry<RootRoute.Folder> { FolderDestination(it, preferences) }
                    entry<RootRoute.FolderPicker> { FolderPickerDestination(it) }
                    entry<RootRoute.SyncConditions> {
                        SyncConditionsDestination(it, preferences)
                    }
                    entry<RootRoute.FolderTypeDialog> { FolderTypeDialogDestination(it) }
                    entry<RootRoute.PullOrderDialog> { PullOrderDialogDestination(it) }
                    entry<RootRoute.VersioningDialog> { VersioningDialogDestination(it) }
                    entry<RootRoute.QRScanner> { QRScannerDestination() }
                },
                transitionSpec = {
                    slideInHorizontally(initialOffsetX = { it }) togetherWith
                            slideOutHorizontally(targetOffsetX = { -it })
                },
                popTransitionSpec = {
                    slideInHorizontally(initialOffsetX = { -it }) togetherWith
                            slideOutHorizontally(targetOffsetX = { it })
                },
                predictivePopTransitionSpec = {
                    slideInHorizontally(initialOffsetX = { -it }) togetherWith
                            slideOutHorizontally(targetOffsetX = { it })
                },
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun MainDestination(
    mainViewModel: MainActivityViewModel,
    configRouter: ConfigRouter,
    preferences: SharedPreferences
) {
    MainActivityScreen(
        viewModel = mainViewModel,
        configRouter = configRouter,
        preferences = preferences
    )
}


@Composable
private fun OnboardingDestination(preferences: SharedPreferences) {
    val context = LocalContext.current
    val activity = context as Activity
    val navigator = LocalRootNavigator.current
    val activityScope = LocalActivityScope.current

    var uiState by rememberSaveable {
        mutableStateOf(
            initialOnboardingUiState(
                context,
                preferences
            )
        )
    }

    lateinit var controller: OnboardingController

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> controller.onPermissionResult(OnboardingPermission.STORAGE, isGranted) }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> controller.onPermissionResult(OnboardingPermission.NOTIFICATION, isGranted) }

    val localNetworkPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        controller.onPermissionResult(
            OnboardingPermission.LOCAL_NETWORK,
            isGranted
        )
    }

    val coarseLocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        controller.onPermissionResult(
            OnboardingPermission.COARSE_LOCATION,
            isGranted
        )
    }

    val backgroundLocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        controller.onPermissionResult(
            OnboardingPermission.BACKGROUND_LOCATION,
            isGranted
        )
    }

    val fineLocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted -> controller.onFineLocationPermissionResult(isGranted) }

    val androidQLocationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> controller.onAndroidQLocationPermissionResult(results) }

    controller = remember {
        OnboardingController(
            context = context,
            activity = activity,
            preferences = preferences,
            navigator = navigator,
            scope = activityScope,
            getUiState = { uiState },
            setUiState = { uiState = it },
            storagePermissionLauncher = storagePermissionLauncher,
            notificationPermissionLauncher = notificationPermissionLauncher,
            localNetworkPermissionLauncher = localNetworkPermissionLauncher,
            coarseLocationPermissionLauncher = coarseLocationPermissionLauncher,
            fineLocationPermissionLauncher = fineLocationPermissionLauncher,
            backgroundLocationPermissionLauncher = backgroundLocationPermissionLauncher,
            androidQLocationPermissionLauncher = androidQLocationPermissionLauncher,
        )
    }

    // Re-derive permissions after the state is restored (rotation) and resume the key-generation
    // coroutine if the flow still sits on the key generation page.
    LaunchedEffect(Unit) {
        controller.restoreState()
    }

    // Auto-advance when the user returns from a system permission dialog.
    val lifecycleOwner = LocalLifecycleOwnerX.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                controller.handleOnResume()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    OnboardingScreen(
        uiState = controller.uiState,
        onBack = controller::handleBack,
        onContinue = controller::advance,
        onFinishOnboarding = controller::finishOnboarding,
        onGrantStoragePermission = controller::requestStoragePermission,
        onGrantLocationPermission = controller::requestLocationPermission,
        onGrantLocalNetworkPermission = controller::requestLocalNetworkPermission,
        onGrantNotificationPermission = controller::requestNotificationPermission,
    )
}

private enum class OnboardingPermission {
    STORAGE,
    NOTIFICATION,
    LOCAL_NETWORK,
    COARSE_LOCATION,
    BACKGROUND_LOCATION,
}

/**
 * State-holder for the onboarding flow. The permission launchers live in the composable (so their
 * results survive activity recreation) but all of the resulting logic lives here.
 */
private class OnboardingController(
    private val context: Context,
    private val activity: Activity,
    private val preferences: SharedPreferences,
    private val navigator: RootNavigator,
    private val scope: CoroutineScope,
    private val getUiState: () -> OnboardingUiState,
    private val setUiState: (OnboardingUiState) -> Unit,
    private val storagePermissionLauncher: ActivityResultLauncher<String>,
    private val notificationPermissionLauncher: ActivityResultLauncher<String>,
    private val localNetworkPermissionLauncher: ActivityResultLauncher<String>,
    private val coarseLocationPermissionLauncher: ActivityResultLauncher<String>,
    private val fineLocationPermissionLauncher: ActivityResultLauncher<String>,
    private val backgroundLocationPermissionLauncher: ActivityResultLauncher<String>,
    private val androidQLocationPermissionLauncher: ActivityResultLauncher<Array<String>>,
) {
    val uiState: OnboardingUiState
        get() = getUiState()

    private fun update(transform: OnboardingUiState.() -> OnboardingUiState) {
        setUiState(transform(uiState))
    }

    fun restoreState() {
        update { copy(keyGenerationRunning = false) }
        refreshPermissionState()
        if (uiState.pages.getOrNull(uiState.currentPage) == OnboardingPage.KEY_GENERATION &&
            !uiState.keyGenerationFailed && !uiState.hasConfig
        ) {
            startKeyGeneration()
        }
    }

    fun onPermissionResult(permission: OnboardingPermission, isGranted: Boolean) {
        refreshPermissionState()
        if (!isGranted) {
            return
        }
        Toast.makeText(context, R.string.permission_granted, Toast.LENGTH_SHORT).show()
        when (permission) {
            OnboardingPermission.STORAGE -> advanceIfCurrentPage(OnboardingPage.STORAGE_PERMISSION)

            OnboardingPermission.NOTIFICATION ->
                advanceIfCurrentPage(OnboardingPage.NOTIFICATION_PERMISSION)

            OnboardingPermission.LOCAL_NETWORK ->
                advanceIfCurrentPage(OnboardingPage.LOCAL_NETWORK_PERMISSION)

            OnboardingPermission.COARSE_LOCATION, OnboardingPermission.BACKGROUND_LOCATION ->
                advanceIfCurrentPage(OnboardingPage.LOCATION_PERMISSION)
        }
    }

    fun onFineLocationPermissionResult(isGranted: Boolean) {
        refreshPermissionState()
        if (!isGranted) {
            return
        }
        Toast.makeText(context, R.string.permission_granted, Toast.LENGTH_SHORT).show()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            backgroundLocationPermissionLauncher.launch(
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            )
        } else {
            advanceIfCurrentPage(OnboardingPage.LOCATION_PERMISSION)
        }
    }

    fun onAndroidQLocationPermissionResult(results: Map<String, Boolean>) {
        refreshPermissionState()
        if (results[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
            Toast.makeText(context, R.string.permission_granted, Toast.LENGTH_SHORT).show()
            advanceIfCurrentPage(OnboardingPage.LOCATION_PERMISSION)
        }
    }

    fun handleBack() {
        if (uiState.keyGenerationRunning) {
            return
        }
        if (uiState.currentPage == 0) {
            navigator.navigateBack()
            return
        }
        moveToPage(uiState.currentPage - 1)
    }

    fun handleOnResume() {
        val oldState = uiState
        refreshPermissionState()
        val currentPage = uiState.pages.getOrNull(uiState.currentPage)
        when {
            currentPage == OnboardingPage.STORAGE_PERMISSION &&
                    !oldState.hasStoragePermission && uiState.hasStoragePermission -> advance()

            currentPage == OnboardingPage.BATTERY_OPTIMIZATION &&
                    !oldState.hasIgnoreDozePermission && uiState.hasIgnoreDozePermission -> advance()

            currentPage == OnboardingPage.LOCAL_NETWORK_PERMISSION &&
                    !oldState.hasLocalNetworkPermission && uiState.hasLocalNetworkPermission -> advance()

            currentPage == OnboardingPage.NOTIFICATION_PERMISSION &&
                    !oldState.hasNotificationPermission && uiState.hasNotificationPermission -> advance()
        }
    }

    fun finishOnboarding() {
        val startIntoWebGui = preferences.getBoolean(Constants.PREF_START_INTO_WEB_GUI, false)
        val targets = if (startIntoWebGui) {
            listOf(RootRoute.Main, RootRoute.WebGui)
        } else {
            listOf(RootRoute.Main)
        }
        // The onboarding flow deliberately never started the foreground service
        // (see MainActivity.onCreate). Start it now that a parseable config exists
        // so syncthing can run and destinations like WebGui can become active.
        val serviceIntent = Intent(context, SyncthingService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
        navigator.resetTo(targets)
    }

    fun requestStoragePermission() {
        PermissionUtil.requestStoragePermission(activity, storagePermissionLauncher)
    }

    fun requestLocationPermission() {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                fineLocationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            }

            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> {
                androidQLocationPermissionLauncher.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    )
                )
            }

            else -> {
                coarseLocationPermissionLauncher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
    }

    fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    fun requestLocalNetworkPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN) {
            localNetworkPermissionLauncher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
    }

    private fun advanceIfCurrentPage(page: OnboardingPage) {
        if (uiState.pages.getOrNull(uiState.currentPage) == page) {
            advance()
        }
    }

    fun advance() {
        val nextPage = uiState.currentPage + 1
        if (nextPage < uiState.pages.size) {
            moveToPage(nextPage)
        } else {
            finishOnboarding()
        }
    }

    private fun moveToPage(page: Int) {
        val boundedPage = page.coerceIn(0, uiState.pages.lastIndex)
        update { copy(currentPage = boundedPage) }
        if (uiState.pages.getOrNull(boundedPage) == OnboardingPage.KEY_GENERATION) {
            startKeyGeneration()
        }
    }

    private fun refreshPermissionState() {
        update {
            copy(
                hasStoragePermission = PermissionUtil.haveStoragePermission(context),
                hasIgnoreDozePermission = context.isIgnoringBatteryOptimizations(),
                hasLocationPermission = haveLocationPermission(context),
                hasLocalNetworkPermission = haveLocalNetworkPermission(context),
                hasNotificationPermission = haveNotificationPermission(context),
                hasConfig = checkForParseableConfig(context),
            )
        }
    }

    private fun startKeyGeneration() {
        if (uiState.keyGenerationRunning || uiState.hasConfig) {
            return
        }
        update {
            copy(
                keyGenerationRunning = true,
                keyGenerationFailed = false,
                keyGenerationStatus = context.getString(R.string.web_gui_creating_key),
            )
        }
        scope.launch {
            val errorMessage = withContext(Dispatchers.IO) {
                try {
                    ConfigXml(context).generateConfig()
                    null
                } catch (e: com.micnubinub.syncthing.service.SyncthingRunnable.ExecutableNotFoundException) {
                    context.getString(R.string.executable_not_found, e.message)
                } catch (_: ConfigXml.OpenConfigException) {
                    context.getString(R.string.config_create_failed)
                }
            }

            if (errorMessage != null) {
                update {
                    copy(
                        keyGenerationRunning = false,
                        keyGenerationFailed = true,
                        keyGenerationStatus = errorMessage,
                    )
                }
                return@launch
            }

            if (!checkForParseableConfig(context)) {
                update {
                    copy(
                        keyGenerationRunning = false,
                        keyGenerationFailed = true,
                        hasConfig = false,
                        keyGenerationStatus = context.getString(R.string.config_read_failed),
                    )
                }
                return@launch
            }

            update {
                copy(
                    keyGenerationRunning = false,
                    keyGenerationFailed = false,
                    hasConfig = true,
                    keyGenerationStatus = context.getString(R.string.key_generation_success),
                )
            }
        }
    }
}

private fun initialOnboardingUiState(
    context: Context,
    preferences: SharedPreferences,
): OnboardingUiState {
    val haveStoragePermission = PermissionUtil.haveStoragePermission(context)
    val haveNotificationPermission = haveNotificationPermission(context)
    val haveLocalNetworkPermission = haveLocalNetworkPermission(context)
    val haveConfig = checkForParseableConfig(context)
    val isRunningOnTv = Util.isRunningOnTV(context)

    if (haveStoragePermission && haveLocalNetworkPermission &&
        haveNotificationPermission && haveConfig
    ) {
        return OnboardingUiState(pages = emptyList())
    }

    val haveIgnoreDozePermission = context.isIgnoringBatteryOptimizations()
    val haveLocationPermission = haveLocationPermission(context)

    return OnboardingUiState(
        pages = listOfNotNull(
            OnboardingPage.WELCOME,
            OnboardingPage.STORAGE_PERMISSION.takeUnless { haveStoragePermission },
            OnboardingPage.BATTERY_OPTIMIZATION.takeUnless { haveIgnoreDozePermission },
            OnboardingPage.LOCATION_PERMISSION.takeUnless { haveLocationPermission },
            OnboardingPage.LOCAL_NETWORK_PERMISSION.takeUnless { haveLocalNetworkPermission },
            OnboardingPage.NOTIFICATION_PERMISSION.takeUnless { haveNotificationPermission },
            OnboardingPage.KEY_GENERATION.takeUnless { haveConfig },
        ),
        hasStoragePermission = haveStoragePermission,
        hasIgnoreDozePermission = haveIgnoreDozePermission,
        hasLocationPermission = haveLocationPermission,
        hasLocalNetworkPermission = haveLocalNetworkPermission,
        hasNotificationPermission = haveNotificationPermission,
        hasConfig = haveConfig,
        isRunningOnTv = isRunningOnTv,
        keyGenerationStatus = context.getString(R.string.web_gui_creating_key),
    )
}

private fun checkForParseableConfig(context: Context): Boolean {
    return Constants.getConfigFile(context).exists()
}

private fun haveNotificationPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return true
    }
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.POST_NOTIFICATIONS
    ) == PackageManager.PERMISSION_GRANTED
}

private fun haveLocalNetworkPermission(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) {
        return true
    }
    return ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_LOCAL_NETWORK
    ) == PackageManager.PERMISSION_GRANTED
}

private fun haveLocationPermission(context: Context): Boolean {
    val coarseLocationGranted = ContextCompat.checkSelfPermission(
        context,
        Manifest.permission.ACCESS_COARSE_LOCATION
    ) == PackageManager.PERMISSION_GRANTED

    val backgroundLocationGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }

    return coarseLocationGranted && backgroundLocationGranted
}

private fun Context.isIgnoringBatteryOptimizations(): Boolean {
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    return pm.isIgnoringBatteryOptimizations(packageName)
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

@Composable
private fun SettingsDestination(startDestination: String?) {
    val rootNavigator = LocalRootNavigator.current
    val backStack = rememberSettingsNavBackStack(SettingsRoute.fromString(startDestination))
    val navigator = remember(backStack, rootNavigator) {
        object : Navigator<SettingsRoute> {
            override fun navigateTo(route: SettingsRoute) {
                backStack.add(route)
            }

            override fun navigateBack() {
                if (backStack.size == 1) {
                    rootNavigator.navigateBack()
                } else {
                    backStack.removeLastOrNull()
                }
            }

            override fun navigateUp() {
                rootNavigator.navigateBack()
            }
        }
    }

    CompositionLocalProvider(LocalSettingsNavigator provides navigator) {
        SettingsNavDisplay(backStack)
    }
}

@Composable
private fun RecentChangesDestination(preferences: SharedPreferences) {
    val context = LocalContext.current
    val service = LocalSyncthingService.current
    val navigator = LocalRootNavigator.current
    val viewModel = viewModel<RecentChangesActivityViewModel>()
    val thisDeviceLabel = stringResource(R.string.this_device)
    var showExactTimes by rememberSaveable {
        mutableStateOf(preferences.getBoolean(Constants.PREF_SHOW_EXACT_TIMES, false))
    }

    LaunchedEffect(Unit) {
        viewModel.setThisDeviceLabel(thisDeviceLabel)
    }

    DisposableEffect(service) {
        viewModel.setService(service)
        val listener = SyncthingService.OnServiceStateChangeListener { state ->
            viewModel.onServiceStateChange(state)
        }
        service?.registerOnServiceStateChangeListener(listener)
        onDispose {
            service?.unregisterOnServiceStateChangeListener(listener)
        }
    }

    RecentChangesActivityScreen(
        viewModel = viewModel,
        showExactTimes = showExactTimes,
        onNavigateBack = { navigator.navigateBack() },
        onToggleExactTimes = {
            showExactTimes = !showExactTimes
            preferences.edit { putBoolean(Constants.PREF_SHOW_EXACT_TIMES, showExactTimes) }
        },
        onItemClick = { diskEvent -> onRecentChangeItemClick(context, service, diskEvent) },
    )
}

private fun onRecentChangeItemClick(
    context: Context,
    service: SyncthingService?,
    diskEvent: DiskEvent,
) {
    if (diskEvent.data.action == "deleted") {
        return
    }
    val restApi = service?.api
    if (restApi == null) {
        Log.e(TAG, "onRecentChangeItemClick: restApi == null")
        return
    }
    val folder = restApi.getFolderByID(diskEvent.data.folderID)
    if (folder == null) {
        Log.e(TAG, "onRecentChangeItemClick: folder == null")
        return
    }
    val path = folder.path + File.separator + diskEvent.data.path
    when (diskEvent.data.type) {
        "dir" -> FileUtils.openFolder(context, path)
        "file" -> FileUtils.openFile(context, path)
        else -> Log.e(
            TAG,
            "onRecentChangeItemClick: Unknown diskEvent.data.type=" + diskEvent.data.type
        )
    }
}

@Composable
private fun LogDestination() {
    val context = LocalContext.current
    val navigator = LocalRootNavigator.current
    val viewModel = viewModel<LogActivityViewModel>()
    val shareLogFileMissingText = stringResource(R.string.share_log_file_missing)
    val shareLogFileText = stringResource(R.string.share_log_file)

    LaunchedEffect(Unit) {
        viewModel.setContext(context.applicationContext)
        viewModel.onAction(LogActivityAction.LoadData)
    }

    LogActivityScreen(
        viewModel = viewModel,
        onShareLogFile = { logFile ->
            if (!logFile.exists()) {
                Toast.makeText(
                    context,
                    shareLogFileMissingText,
                    Toast.LENGTH_SHORT
                ).show()
                return@LogActivityScreen
            }
            val contentUri = FileProvider.getUriForFile(
                context,
                context.packageName + ".provider",
                logFile
            )
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, contentUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                Intent.createChooser(
                    shareIntent,
                    shareLogFileText
                )
            )
        },
        onNavigateBack = { navigator.navigateBack() },
    )
}

@Composable
private fun WebViewDestination(url: String) {
    val context = LocalContext.current
    val navigator = LocalRootNavigator.current
    val viewModel = viewModel<WebViewActivityViewModel>()

    LaunchedEffect(Unit) {
        viewModel.setPreferences(
            PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        )
        viewModel.onAction(
            WebViewActivityAction.Initialize(url, Util.isRunningOnTV(context))
        )
    }

    WebViewActivityScreen(
        viewModel = viewModel,
        onOpenInBrowser = {
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            navigator.navigateBack()
        },
        onNavigateBack = { navigator.navigateBack() },
    )
}

// ---------------------------------------------------------------------------
// Web GUI
// ---------------------------------------------------------------------------

@Composable
private fun WebGuiDestination() {
    val context = LocalContext.current
    val navigator = LocalRootNavigator.current
    val lifecycleOwner = LocalLifecycleOwnerX.current
    val service = LocalSyncthingService.current

    var webGuiLoading by remember { mutableStateOf(true) }
    var webGuiLoadError by remember { mutableStateOf<String?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var webGuiLoadStarted by remember { mutableStateOf(false) }
    var serviceActive by remember { mutableStateOf(false) }

    // Load the configuration and CA certificate once; bail out when the certificate is missing,
    // mirroring WebGuiActivity's early finish().
    val loaded by produceState<Triple<URL, java.security.cert.X509Certificate?, ConfigXml>?>(null) {
        value = withContext(Dispatchers.IO) {
            try {
                val config = ConfigXml(context).apply { loadConfig() }
                Triple(toLoopback(config.webGuiUrl), loadCaCertificate(context), config)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load config and certificate", e)
                null
            }
        }
    }
    val loadedState = loaded ?: return
    val webGuiUrl = loadedState.first
    val caCertificate = loadedState.second

    if (caCertificate == null) {
        LaunchedEffect(Unit) {
            Toast.makeText(context, R.string.config_file_missing, Toast.LENGTH_LONG).show()
            navigator.navigateBack()
        }
        return
    }

    val webViewClient = remember(webGuiUrl, caCertificate) {
        object : WebViewClient() {
            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError,
            ) {
                handleSslError(handler, error, caCertificate, webGuiUrl)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = shouldOpenOutsideWebView(context, request.url, webGuiUrl)

            // The WebResourceRequest overload above is only dispatched on API >= 24; minSdk is 23,
            // so the deprecated String overload is required to intercept links on API 23 devices.
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                shouldOpenOutsideWebView(context, url.toUri(), webGuiUrl)

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (!request.isForMainFrame || !webGuiLoading) {
                    return
                }
                Log.w(TAG, "Web GUI load failed: ${error.errorCode} ${error.description}")
                webGuiLoadError = "${error.description} (${error.errorCode})"
            }

            override fun onPageFinished(view: WebView, url: String) {
                webGuiLoading = false
            }
        }
    }

    fun loadWebGuiIfNeeded() {
        val currentWebView = webView
        if (currentWebView == null) {
            Log.v(TAG, "loadWebGuiIfNeeded: Skipped event due to webView == null")
            return
        }
        if (!serviceActive || webGuiLoadStarted || currentWebView.url != null) {
            return
        }
        webGuiLoadStarted = true
        currentWebView.stopLoading()

        loaded?.third?.let {
            currentWebView.loadUrl(webGuiUrl.toString(), createAuthHeaders(it))
        }
    }

    // The service listener is invoked immediately with the current state, so the initial load is
    // also attempted here (the WebView may be created lazily after the service is already active).
    DisposableEffect(service) {
        val listener = SyncthingService.OnServiceStateChangeListener { state ->
            serviceActive = state == SyncthingService.State.ACTIVE
            loadWebGuiIfNeeded()
        }
        service?.registerOnServiceStateChangeListener(listener)
        onDispose {
            service?.unregisterOnServiceStateChangeListener(listener)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    webView?.onPause()
                }

                Lifecycle.Event.ON_RESUME -> {
                    webView?.onResume()
                }

                Lifecycle.Event.ON_DESTROY -> {
                    webView?.destroy()
                    webView = null
                }

                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            webView?.destroy()
            webView = null
        }
    }

    WebGuiScreen(
        loading = webGuiLoading,
        error = webGuiLoadError,
        onNavigateBack = {
            val currentWebView = webView
            if (currentWebView?.canGoBack() == true) {
                currentWebView.goBack()
            } else {
                navigator.navigateBack()
            }
        },
        webViewFactory = { factoryContext ->
            createWebView(factoryContext, webViewClient).also {
                webView = it
                // The WebView is created lazily during composition, which may run after the
                // service already reported ACTIVE. Attempt the load here so we don't miss that
                // initial state and stay stuck on the spinner.
                loadWebGuiIfNeeded()
            }
        },
    )
}

@SuppressLint("SetJavaScriptEnabled")
private fun createWebView(context: Context, webViewClient: WebViewClient): WebView =
    WebView(context).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        // The GUI is served over the network from the local Syncthing instance; it never needs
        // to read local files, so deny file/content access to limit exfiltration if compromised.
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        this.webViewClient = webViewClient
        clearCache(true)
    }

private fun toLoopback(url: URL): URL {
    val port = if (url.port != -1) url.port else Constants.DEFAULT_WEBGUI_TCP_PORT
    return URL(url.protocol, "127.0.0.1", port, url.file)
}

private fun loadCaCertificate(context: Context): X509Certificate? {
    val httpsCertFile: File = Constants.getHttpsCertFile(context)
    if (!httpsCertFile.exists()) {
        return null
    }
    return try {
        FileInputStream(httpsCertFile).use { input: InputStream ->
            val certificateFactory = CertificateFactory.getInstance("X.509")
            certificateFactory.generateCertificate(input) as? X509Certificate
        }
    } catch (e: Exception) {
        Log.w(TAG, "Failed to parse HTTPS certificate", e)
        null
    }
}

private fun createAuthHeaders(config: ConfigXml): Map<String, String> {
    val credentials = "${config.webUIUsername}:${config.webUIPassword}"
    val encodedCredentials = Base64.encodeToString(
        credentials.toByteArray(Charset.forName("UTF-8")),
        Base64.NO_WRAP,
    )
    return mapOf("Authorization" to "Basic $encodedCredentials")
}

private fun shouldOpenOutsideWebView(
    context: Context,
    uri: Uri,
    webGuiUrl: URL,
): Boolean {
    val host = uri.host
    val webGuiHost = webGuiUrl.host
    val scheme = uri.scheme
    val webGuiScheme = webGuiUrl.protocol
    val port = uri.port
    val webGuiPort = webGuiUrl.port

    // Only keep the URL inside the WebView when it matches the exact GUI origin
    // (scheme, host, and port). Different scheme or port means a different origin
    // and must be opened externally so the auth header is never forwarded.
    // Host and scheme comparisons are lower-cased because both are case-insensitive.
    if (host != null && host.lowercase() == webGuiHost.lowercase() &&
        scheme?.lowercase() == webGuiScheme.lowercase() && port == webGuiPort
    ) {
        return false
    }
    if (!Util.isRunningOnTV(context)) {
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(context, R.string.no_app_to_open_link, Toast.LENGTH_SHORT).show()
        }
    }
    return true
}

private fun handleSslError(
    handler: SslErrorHandler,
    error: SslError,
    caCertificate: X509Certificate?,
    webGuiUrl: URL
) {
    // The WebView only ever connects to the local Syncthing instance over loopback. When the
    // certificate chain is trusted by the OS (see the loopback domain-config in
    // network_security_config.xml) but the hostname does not match -- e.g. a user-supplied
    // CA-signed certificate without a 127.0.0.1 SAN -- the only remaining error is
    // SSL_IDMISMATCH. Proceed in that case, mirroring the REST API path which likewise skips
    // hostname verification for the local connection. Untrusted, expired, and not-yet-valid
    // certificates are not accepted here; they fall through to self-signed pinning below.
    if (error.primaryError == SslError.SSL_IDMISMATCH &&
        !error.hasError(SslError.SSL_EXPIRED) &&
        !error.hasError(SslError.SSL_NOTYETVALID)
    ) {
        val errorUrl = URL(error.url)
        val errorHost = errorUrl.host
        val errorPort =
            errorUrl.port.takeIf { it != -1 } ?: if (errorUrl.protocol == "https") 443 else 80
        val guiPort =
            webGuiUrl.port.takeIf { it != -1 } ?: if (webGuiUrl.protocol == "https") 443 else 80
        val isLoopback = errorHost == "127.0.0.1" || errorHost == "localhost"
        if (isLoopback && errorPort == guiPort) {
            try {
                val certificate = extractCertificate(error.certificate)
                if (certificate == null || caCertificate == null) {
                    Log.w(TAG, "X509Certificate reference invalid")
                    handler.cancel()
                    return
                }
                certificate.verify(caCertificate.publicKey)
                handler.proceed()
            } catch (e: Exception) {
                Log.w(TAG, e)
                handler.cancel()
            }
            return
        }
    }

    // Otherwise, fall back to pinning against the local instance's self-signed certificate.
    try {
        val certificate = extractCertificate(error.certificate)
        val ca = caCertificate
        if (certificate == null || ca == null) {
            Log.w(TAG, "X509Certificate reference invalid")
            handler.cancel()
            return
        }
        certificate.verify(ca.publicKey)
        handler.proceed()
    } catch (e: Exception) {
        Log.w(TAG, e)
        handler.cancel()
    }
}

@SuppressLint("PrivateApi")
private fun extractCertificate(sslCertificate: SslCertificate): X509Certificate? {
    return try {
        val certificateField = sslCertificate.javaClass.getDeclaredField("mX509Certificate")
        certificateField.isAccessible = true
        certificateField.get(sslCertificate) as? X509Certificate
    } catch (e: Exception) {
        Log.w(TAG, "Could not extract X509Certificate", e)
        null
    }
}

// ---------------------------------------------------------------------------
// Device / Folder edit screens
// ---------------------------------------------------------------------------

@Composable
private fun DeviceDestination(route: RootRoute.Device, preferences: SharedPreferences) {
    val context = LocalContext.current
    val navigator = LocalRootNavigator.current
    val service = LocalSyncthingService.current
    val viewModel = viewModel<DeviceActivityViewModel>()
    val configRouter = remember(context) { ConfigRouter(context) }
    val compressionValues = stringArrayResource(R.array.compress_values).toList()

    LaunchedEffect(route, configRouter) {
        viewModel.initialize(
            config = configRouter,
            preferences = preferences,
            compressionValues = compressionValues,
            expertMode = preferences.getBoolean(Constants.PREF_EXPERT_MODE, false),
            showQrScannerButton = !Util.isRunningOnTV(context),
            isCreateMode = route.isCreate,
            deviceId = route.deviceId,
            deviceName = route.deviceName,
        )
    }

    DisposableEffect(service, route) {
        service?.notificationHandler?.cancelConsentNotification(route.notificationId)
        viewModel.onServiceConnected(service?.api)
        onDispose {
            service?.notificationHandler?.cancelConsentNotification(route.notificationId)
        }
    }

    DeviceActivityScreen(
        viewModel = viewModel,
        onFinish = { resultCode ->
            navigator.deliverResult(route, resultCode)
        },
        onScanQrCode = {
            navigator.navigateTo(RootRoute.QRScanner) { result ->
                (result as? String)?.let {
                    viewModel.onAction(DeviceActivityAction.QrCodeScanned(it))
                }
            }
        },
        onAddFolder = {
            navigator.navigateTo(RootRoute.Folder(isCreate = true)) { result ->
                if (result == Activity.RESULT_OK) {
                    viewModel.onAction(DeviceActivityAction.FoldersChanged)
                }
            }
        },
        onEditSyncConditions = { objectPrefixAndId, readableName ->
            navigator.navigateTo(
                RootRoute.SyncConditions(objectPrefixAndId.orEmpty(), readableName)
            )
        },
    )
}

@Composable
private fun FolderDestination(route: RootRoute.Folder, preferences: SharedPreferences) {
    val context = LocalContext.current
    val navigator = LocalRootNavigator.current
    val service = LocalSyncthingService.current
    val viewModel = viewModel<FolderActivityViewModel>()
    val configRouter = remember(context) { ConfigRouter(context) }
    val sessionKey = rememberSaveable { UUID.randomUUID().toString() }

    LaunchedEffect(route, configRouter) {
        viewModel.initialize(
            context = context.applicationContext,
            config = configRouter,
            preferences = preferences,
            expertMode = preferences.getBoolean(Constants.PREF_EXPERT_MODE, false),
            isCreateMode = route.isCreate,
            folderId = route.folderId,
            folderLabel = route.folderLabel,
            shareWithDeviceId = route.shareWithDeviceId,
            receiveEncrypted = route.receiveEncrypted,
            sessionKey = sessionKey,
        )
    }

    DisposableEffect(service, route) {
        service?.notificationHandler?.cancelConsentNotification(route.notificationId)
        viewModel.onServiceConnected(service?.api)
        onDispose {
            service?.notificationHandler?.cancelConsentNotification(route.notificationId)
        }
    }

    val chooseFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            return@rememberLauncherForActivityResult
        }
        val folderUri = result.data?.data ?: return@rememberLauncherForActivityResult
        var targetPath = FileUtils.getAbsolutePathFromSAFUri(context, folderUri)
        if (targetPath != null) {
            targetPath = Util.formatPath(targetPath)
        }
        viewModel.onAction(FolderActivityAction.FolderPathSelected(targetPath, folderUri))
    }

    FolderActivityScreen(
        viewModel = viewModel,
        onFinish = { resultCode ->
            navigator.deliverResult(route, resultCode)
        },
        onPickFolder = {
            pickFolderWithSaf(context, viewModel, chooseFolderLauncher, navigator)
        },
        onPickFolderAdvanced = {
            navigator.navigateTo(
                RootRoute.FolderPicker(viewModel.state.value.path, null)
            ) { result ->
                viewModel.onAction(
                    FolderActivityAction.FolderPathSelected(
                        FileUtils.cutTrailingSlash(result as? String),
                        null
                    )
                )
            }
        },
        onShowFolderTypeDialog = { currentType ->
            navigator.navigateTo(RootRoute.FolderTypeDialog(currentType)) { result ->
                viewModel.onAction(
                    FolderActivityAction.FolderTypeSelected(result as? String ?: "")
                )
            }
        },
        onShowPullOrderDialog = { currentOrder ->
            navigator.navigateTo(RootRoute.PullOrderDialog(currentOrder)) { result ->
                viewModel.onAction(
                    FolderActivityAction.PullOrderSelected(result as? String ?: "")
                )
            }
        },
        onShowVersioningDialog = { type, params ->
            navigator.navigateTo(RootRoute.VersioningDialog(type, params)) { result ->
                (result as? VersioningResult)?.let {
                    viewModel.onAction(
                        FolderActivityAction.VersioningSelected(it.type, it.params)
                    )
                }
            }
        },
        onAddDevice = {
            navigator.navigateTo(RootRoute.Device(isCreate = true)) { result ->
                if (result == Activity.RESULT_OK) {
                    viewModel.onAction(FolderActivityAction.DevicesChanged)
                }
            }
        },
        onEditSyncConditions = { objectPrefixAndId, readableName ->
            navigator.navigateTo(
                RootRoute.SyncConditions(objectPrefixAndId.orEmpty(), readableName)
            )
        },
    )
}

private fun pickFolderWithSaf(
    context: Context,
    viewModel: FolderActivityViewModel,
    chooseFolderLauncher: androidx.activity.compose.ManagedActivityResultLauncher<Intent, androidx.activity.result.ActivityResult>,
    navigator: RootNavigator,
) {
    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)

    // Determine directory initialUri for SAF file picker dialog.
    var initialUri: Uri? = null
    FileUtils.getExternalFilesDirUri(context, FileUtils.ExternalStorageDirType.INT_MEDIA)
        ?.let { externalFilesDirUri ->
            if (FileUtils.directoryUriExists(context, externalFilesDirUri)) {
                initialUri = externalFilesDirUri
            } else {
                val internalFilesDirUri = FileUtils.internalStorageRootUri
                if (FileUtils.directoryUriExists(context, internalFilesDirUri)) {
                    initialUri = internalFilesDirUri
                }
            }
        }

    if (initialUri != null) {
        Log.v(TAG, "pickFolderWithSaf: INITIAL_URI = $initialUri")
        intent.putExtra("android.provider.extra.INITIAL_URI", initialUri)
    }

    // Display storage access framework directory picker UI.
    intent.putExtra(Intent.EXTRA_LOCAL_ONLY, true)
    intent.putExtra("android.content.extra.SHOW_ADVANCED", true)
    try {
        chooseFolderLauncher.launch(intent)
    } catch (e: ActivityNotFoundException) {
        Log.e(
            TAG,
            "pickFolderWithSaf exception, falling back to built-in FolderPicker.",
            e
        )
        navigator.navigateTo(
            RootRoute.FolderPicker(viewModel.state.value.path, null)
        ) { result ->
            viewModel.onAction(
                FolderActivityAction.FolderPathSelected(
                    FileUtils.cutTrailingSlash(result as? String),
                    null
                )
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Folder picker
// ---------------------------------------------------------------------------

@Composable
private fun FolderPickerDestination(route: RootRoute.FolderPicker) {
    val context = LocalContext.current
    val navigator = LocalRootNavigator.current
    val viewModel = viewModel<FolderPickerActivityViewModel>()

    LaunchedEffect(route) {
        viewModel.setExternalFilesDirs(context.getExternalFilesDirs(null))
        viewModel.setExternalFilesDir(context.getExternalFilesDir(null))
        viewModel.setInitialDirectory(route.initialDirectory)
        viewModel.setRootDirectory(route.rootDirectory)
        viewModel.onAction(FolderPickerActivityAction.LoadData)
    }

    FolderPickerActivityScreen(
        viewModel = viewModel,
        onSelectDirectory = { path ->
            navigator.deliverResult(route, path)
        },
        onNavigateUp = { navigator.navigateBack() },
    )
}

// ---------------------------------------------------------------------------
// Sync conditions
// ---------------------------------------------------------------------------

@Composable
private fun SyncConditionsDestination(
    route: RootRoute.SyncConditions,
    preferences: SharedPreferences,
) {
    val navigator = LocalRootNavigator.current
    val viewModel = viewModel<SyncConditionsActivityViewModel>()

    LaunchedEffect(route) {
        viewModel.setPreferences(preferences)
        viewModel.setObjectPrefixAndId(route.objectPrefixAndId)
        viewModel.onAction(SyncConditionsActivityAction.LoadData)
    }

    SyncConditionsActivityScreen(
        viewModel = viewModel,
        onNavigateUp = { navigator.navigateBack() },
    )
}

// ---------------------------------------------------------------------------
// Dialogs
// ---------------------------------------------------------------------------

@Composable
private fun FolderTypeDialogDestination(route: RootRoute.FolderTypeDialog) {
    val navigator = LocalRootNavigator.current
    val viewModel = viewModel<FolderTypeDialogActivityViewModel>()

    LaunchedEffect(route) {
        viewModel.setInitialArguments(route.currentType)
    }

    FolderTypeDialogActivityScreen(
        viewModel = viewModel,
        onSave = {
            navigator.deliverResult(route, viewModel.state.value.selectedType)
        },
    )
}

@Composable
private fun PullOrderDialogDestination(route: RootRoute.PullOrderDialog) {
    val navigator = LocalRootNavigator.current
    val viewModel = viewModel<PullOrderDialogActivityViewModel>()

    LaunchedEffect(route) {
        viewModel.setInitialArguments(route.currentOrder)
    }

    PullOrderDialogActivityScreen(
        viewModel = viewModel,
        onSave = {
            navigator.deliverResult(route, viewModel.state.value.selectedType)
        },
    )
}

@Composable
private fun VersioningDialogDestination(route: RootRoute.VersioningDialog) {
    val navigator = LocalRootNavigator.current
    val viewModel = viewModel<VersioningDialogActivityViewModel>()

    LaunchedEffect(route) {
        viewModel.setInitialArguments(
            Bundle().apply {
                route.params.forEach { (key, value) -> putString(key, value) }
                putString("type", route.type)
            }
        )
    }

    VersioningDialogActivityScreen(
        viewModel = viewModel,
        onSave = {
            val state = viewModel.state.value
            navigator.deliverResult(
                route,
                VersioningResult(state.selectedType, state.parameters),
            )
        },
    )
}

@Composable
private fun QRScannerDestination() {
    val navigator = LocalRootNavigator.current
    val viewModel = viewModel<QRScannerActivityViewModel>()

    QRScannerActivityScreen(
        viewModel = viewModel,
        onBarcodeResult = { code ->
            navigator.deliverResult(RootRoute.QRScanner, code)
        },
        onCancel = { navigator.navigateBack() },
    )
}
