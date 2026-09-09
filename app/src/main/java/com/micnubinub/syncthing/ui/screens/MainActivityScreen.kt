package com.micnubinub.syncthing.ui.screens

import android.content.Intent
import android.content.SharedPreferences
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.ViewQuilt
import androidx.compose.material.icons.filled.AddToQueue
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.outlined.ImportExport
import androidx.compose.material.icons.outlined.QrCode2
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.runtime.serialization.NavBackStackSerializer
import androidx.navigation3.runtime.serialization.NavKeySerializer
import androidx.navigation3.ui.NavDisplay
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.activities.MainActivity
import com.micnubinub.syncthing.navigation.LocalRootNavigator
import com.micnubinub.syncthing.navigation.RootRoute
import com.micnubinub.syncthing.service.AppPrefs
import com.micnubinub.syncthing.service.Constants
import com.micnubinub.syncthing.service.SyncthingService
import com.micnubinub.syncthing.theme.ApplicationTheme
import com.micnubinub.syncthing.ui.components.SyncStatusButton
import com.micnubinub.syncthing.ui.screens.fragments.DeviceFragmentScreen
import com.micnubinub.syncthing.ui.screens.fragments.DeviceIdDialog
import com.micnubinub.syncthing.ui.screens.fragments.FolderFragmentScreen
import com.micnubinub.syncthing.ui.screens.fragments.StatusFragmentScreen
import com.micnubinub.syncthing.ui.screens.fragments.UsageReportingChoice
import com.micnubinub.syncthing.ui.screens.fragments.UsageReportingDialog
import com.micnubinub.syncthing.ui.viewModels.DeviceFragmentViewModel
import com.micnubinub.syncthing.ui.viewModels.FolderFragmentViewModel
import com.micnubinub.syncthing.ui.viewModels.MainActivityState
import com.micnubinub.syncthing.ui.viewModels.MainActivityViewModel
import com.micnubinub.syncthing.ui.viewModels.StatusActivityViewModel
import com.micnubinub.syncthing.util.ConfigRouter
import com.micnubinub.syncthing.util.isTelevision
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

@Serializable
sealed interface MainTabRoute : NavKey {
    @Serializable
    data object Folders : MainTabRoute

    @Serializable
    data object Devices : MainTabRoute

    @Serializable
    data object Status : MainTabRoute
}

enum class Destination(
    val route: MainTabRoute,
    val label: String,
    val icon: ImageVector,
    val contentDescription: String
) {
    FOLDERS(MainTabRoute.Folders, "Folders", Icons.Default.Folder, "Folders"),
    DEVICES(MainTabRoute.Devices, "Devices", Icons.Default.Devices, "Devices"),
    STATUS(MainTabRoute.Status, "Status", Icons.Default.Sync, "Status")
}

@Preview(showSystemUi = true)
@Composable
fun MainActivityScreen(
    modifier: Modifier = Modifier,
    viewModel: MainActivityViewModel = MainActivityViewModel(),
    configRouter: ConfigRouter? = null,
    preferences: SharedPreferences? = null
) {
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
    val deviceIdDialog by viewModel.deviceIdDialog.collectAsStateWithLifecycle()
    val usageReportingDialog by viewModel.usageReportingDialog.collectAsStateWithLifecycle()
    val state by viewModel.state.collectAsStateWithLifecycle()

    MainActivityContent(
        serviceState = serviceState,
        state = state,
        modifier = modifier,
        viewModel = viewModel,
        configRouter = configRouter
    )

    deviceIdDialog?.let { dialog ->
        DeviceIdDialog(
            deviceName = dialog.deviceName,
            deviceId = dialog.deviceId,
            isCurrentDevice = true,
            onDismiss = { viewModel.dismissDeviceIdDialog() }
        )
    }

    usageReportingDialog?.let { dialog ->
        val navigator = LocalRootNavigator.current
        UsageReportingDialog(
            report = dialog.report,
            onChoice = { choice ->
                val api = viewModel.api.value
                when (choice) {
                    UsageReportingChoice.ACCEPT -> {
                        api?.setUsageReporting(true)
                        api?.sendConfig()
                        AppPrefs.setUsageReportingDialogAnswered(preferences)
                    }

                    UsageReportingChoice.DENY -> {
                        api?.setUsageReporting(false)
                        api?.sendConfig()
                        AppPrefs.setUsageReportingDialogAnswered(preferences)
                    }

                    UsageReportingChoice.OPEN_WEBSITE -> {
                        navigator.navigateTo(
                            RootRoute.WebView("https://data.syncthing.net")
                        )
                    }
                }
                viewModel.dismissUsageReportingDialog()
            },
            onDismiss = { viewModel.dismissUsageReportingDialog() },
        )
    }
}

@Composable
private inline fun <reified T : MainTabRoute> rememberMainTabBackStack(initial: T): NavBackStack<T> {
    return androidx.compose.runtime.saveable.rememberSerializable(
        serializer = NavBackStackSerializer(elementSerializer = NavKeySerializer()),
    ) { NavBackStack(initial) }
}

@Composable
private inline fun <reified T : MainTabRoute> tabEntries(
    backStack: NavBackStack<T>,
    initial: T,
    noinline content: @Composable () -> Unit,
): List<NavEntry<MainTabRoute>> {
    if (backStack.isEmpty()) {
        backStack.add(initial)
    }
    val decorators = listOf(rememberSaveableStateHolderNavEntryDecorator<T>())
    val provider = entryProvider<T> {
        entry<T> { content() }
    }
    @Suppress("UNCHECKED_CAST")
    return rememberDecoratedNavEntries(
        backStack = backStack,
        entryDecorators = decorators,
        entryProvider = provider,
    ) as List<NavEntry<MainTabRoute>>
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainActivityContent(
    serviceState: SyncthingService.State,
    state: MainActivityState,
    viewModel: MainActivityViewModel,
    configRouter: ConfigRouter?,
    modifier: Modifier = Modifier
) {
    val navigator = LocalRootNavigator.current
    val scope = rememberCoroutineScope()
    val confirmDrawerStateChange = remember(viewModel) {
        { confirm: DrawerValue ->
            viewModel.setDrawerState(confirm)
            true
        }
    }
    val drawerState = rememberDrawerState(
        initialValue = state.drawerState,
        confirmStateChange = confirmDrawerStateChange
    )
    var selectedDestination by rememberSaveable { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(
        initialPage = selectedDestination,
        pageCount = { Destination.entries.size }
    )

    val currentPage by remember { derivedStateOf { pagerState.currentPage } }
    var isScrollingProgrammatically by remember { mutableStateOf(false) }

    // Keep the pager and the selected tab in sync, so taps on the tab row and
    // swipe gestures both move the pager and update the tab indicator.
    LaunchedEffect(selectedDestination) {
        if (pagerState.currentPage != selectedDestination) {
            try {
                isScrollingProgrammatically = true
                pagerState.animateScrollToPage(selectedDestination)
            } finally {
                isScrollingProgrammatically = false
            }
        }
    }
    LaunchedEffect(currentPage) {
        if (!isScrollingProgrammatically) {
            selectedDestination = currentPage
        }
    }

    // Sync externally requested drawer state (e.g. the TV menu key in MainActivity) into the drawer.
    LaunchedEffect(state.drawerState) {
        if (state.drawerState == DrawerValue.Open) {
            drawerState.open()
        } else {
            drawerState.close()
        }
    }

    val foldersBackStack = rememberMainTabBackStack(MainTabRoute.Folders)
    val devicesBackStack = rememberMainTabBackStack(MainTabRoute.Devices)
    val statusBackStack = rememberMainTabBackStack(MainTabRoute.Status)
    val folderViewModel = remember { FolderFragmentViewModel() }
    val deviceViewModel = remember { DeviceFragmentViewModel() }
    val application = LocalContext.current.applicationContext as android.app.Application
    val statusViewModel = remember { StatusActivityViewModel(application) }

    val onCloseDrawer: () -> Unit = remember(scope) {
        { scope.launch { drawerState.close() } }
    }
    val onMenuClick: () -> Unit = remember(viewModel, scope) {
        {
            scope.launch {
                if (drawerState.isClosed) {
                    viewModel.setDrawerState(DrawerValue.Open)
                } else {
                    viewModel.setDrawerState(DrawerValue.Closed)
                }
            }
        }
    }
    val onSyncStatusClick: () -> Unit = remember(configRouter, viewModel, selectedDestination) {
        {
            configRouter?.let {
                viewModel.update(
                    it,
                    Destination.entries[selectedDestination]
                )
            }
        }
    }
    val onAddFolderClick = remember(navigator) {
        { navigator.navigateTo(RootRoute.Folder(isCreate = true)) }
    }
    val onAddDeviceClick = remember(navigator) {
        { navigator.navigateTo(RootRoute.Device(isCreate = true)) }
    }
    val onOpenSettingsClick = remember(navigator) {
        { navigator.navigateTo(RootRoute.Settings()) }
    }

    val foldersEntries = tabEntries(foldersBackStack, MainTabRoute.Folders) {
        FolderFragmentScreen(
            serviceFlow = viewModel.service,
            serviceStateFlow = viewModel.serviceState,
            configRouter = configRouter,
            viewModel = folderViewModel
        )
    }
    val devicesEntries = tabEntries(devicesBackStack, MainTabRoute.Devices) {
        DeviceFragmentScreen(
            serviceFlow = viewModel.service,
            serviceStateFlow = viewModel.serviceState,
            configRouter = configRouter,
            viewModel = deviceViewModel
        )
    }
    val statusEntries = tabEntries(statusBackStack, MainTabRoute.Status) {
        StatusFragmentScreen(
            serviceStateFlow = viewModel.serviceState,
            serviceFlow = viewModel.service,
            viewModel = statusViewModel
        )
    }

    ApplicationTheme {
        ModalNavigationDrawer(
            drawerState = drawerState,
            drawerContent = {
                MainDrawerContent(
                    stServiceRunning = serviceState == SyncthingService.State.ACTIVE,
                    onClose = onCloseDrawer,
                )
            }
        ) {
            Scaffold(
                modifier = modifier,
                topBar = {
                    TopAppBar(
                        title = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(stringResource(R.string.app_name))
                                Spacer(modifier.width(8.dp))
                                SyncStatusButton(
                                    syncState = serviceState,
                                    isSyncing = state.isSyncing,
                                    onClick = onSyncStatusClick
                                )
                                Spacer(modifier.weight(1f))
                                when (Destination.entries[selectedDestination]) {
                                    Destination.FOLDERS -> IconButton(
                                        onClick = onAddFolderClick,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.CreateNewFolder,
                                            contentDescription = stringResource(R.string.add_folder)
                                        )
                                    }

                                    Destination.DEVICES -> IconButton(
                                        onClick = onAddDeviceClick,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.AddToQueue,
                                            contentDescription = stringResource(R.string.add_device)
                                        )
                                    }

                                    Destination.STATUS -> IconButton(
                                        onClick = onOpenSettingsClick,
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = stringResource(R.string.settings_title)
                                        )
                                    }
                                }
                            }
                        },

                        navigationIcon = {
                            IconButton(onClick = onMenuClick) {
                                Icon(
                                    Icons.Default.Menu,
                                    contentDescription = "Back"
                                )
                            }
                        }
                    )
                }
            ) { contentPadding ->
                Column(
                    modifier = Modifier
                        .padding(contentPadding)
                        .fillMaxSize()
                ) {
                    PrimaryTabRow(
                        selectedTabIndex = selectedDestination
                    ) {
                        Destination.entries.forEachIndexed { index, destination ->
                            val onTabClick = remember(index) {
                                { selectedDestination = index }
                            }
                            Tab(
                                selected = selectedDestination == index,
                                onClick = onTabClick,
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Icon(
                                            destination.icon,
                                            contentDescription = destination.contentDescription
                                        )
                                        Text(
                                            text = destination.label,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            )
                        }
                    }
                    HorizontalPager(
                        state = pagerState,
                        modifier = Modifier.weight(1f),
                    ) { pageIndex ->
                        when (pageIndex) {
                            0 -> MainTabNavDisplay(
                                entries = foldersEntries,
                                onBack = {
                                    if (foldersBackStack.size > 1) {
                                        foldersBackStack.removeLastOrNull()
                                    } else {
                                        navigator.navigateBack()
                                    }
                                },
                            )

                            1 -> MainTabNavDisplay(
                                entries = devicesEntries,
                                onBack = {
                                    if (devicesBackStack.size > 1) {
                                        devicesBackStack.removeLastOrNull()
                                    } else {
                                        navigator.navigateBack()
                                    }
                                },
                            )

                            else -> MainTabNavDisplay(
                                entries = statusEntries,
                                onBack = {
                                    if (statusBackStack.size > 1) {
                                        statusBackStack.removeLastOrNull()
                                    } else {
                                        navigator.navigateBack()
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    // Close the drawer instead of exiting when back is pressed while the drawer is open.
    BackHandler(enabled = drawerState.isOpen) {
        viewModel.setDrawerState(DrawerValue.Closed)
    }
}

@Composable
private fun MainTabNavDisplay(
    entries: List<NavEntry<MainTabRoute>>,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    NavDisplay(
        entries = entries,
        onBack = onBack,
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

@Composable
private fun MainDrawerContent(
    stServiceRunning: Boolean,
    onClose: () -> Unit,
) {
    val activity = LocalActivity.current as? MainActivity
    val navigator = LocalRootNavigator.current
    val config = LocalConfiguration.current

    ModalDrawerSheet(
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .wrapContentWidth()
                .padding(12.dp)
        ) {
            DrawerHeader()
            HorizontalDivider(Modifier.padding(horizontal = 16.dp))

            LazyColumn(
                modifier = Modifier
                    .padding(vertical = 8.dp)
                    .weight(1f)
            ) {
                item {
                    DrawerItem(
                        icon = { Icon(Icons.Outlined.QrCode2, null) },
                        label = { Text(stringResource(R.string.show_device_id)) },
                        onClick = {
                            activity?.showQrCodeDialog()
                            onClose()
                        },
                    )
                }
                item {
                    DrawerItem(
                        icon = { Icon(Icons.Outlined.Restore, null) },
                        label = { Text(stringResource(R.string.recent_changes_title)) },
                        onClick = {
                            navigator.navigateTo(RootRoute.RecentChanges)
                            onClose()
                        },
                        enabled = stServiceRunning,
                    )
                }

                if (activity?.let { Constants.isDebuggable(it) } == true ||
                    !config.isTelevision
                ) {
                    item {
                        DrawerItem(
                            icon = { Icon(Icons.AutoMirrored.Outlined.ViewQuilt, null) },
                            label = { Text(stringResource(R.string.web_gui_title)) },
                            onClick = {
                                navigator.navigateTo(RootRoute.WebGui)
                                onClose()
                            },
                            enabled = stServiceRunning,
                        )
                    }
                }

                item {
                    DrawerItem(
                        icon = { Icon(Icons.Outlined.ImportExport, null) },
                        label = { Text(stringResource(R.string.category_backup)) },
                        onClick = {
                            navigator.navigateTo(RootRoute.Settings("ImportExport"))
                            onClose()
                        },
                    )
                }
                item {
                    RestartDrawerItem(stServiceRunning, onClose)
                }
            }

            HorizontalDivider(Modifier.padding(horizontal = 16.dp))

            Column(
                modifier = Modifier.padding(vertical = 8.dp)
            ) {
                DrawerItem(
                    icon = { Icon(Icons.Outlined.Settings, null) },
                    label = { Text(stringResource(R.string.settings_title)) },
                    onClick = {
                        navigator.navigateTo(RootRoute.Settings())
                        onClose()
                    },
                )
                ExitDrawerItem()
            }
        }
    }
}

@Composable
private fun RestartDrawerItem(stServiceRunning: Boolean, onClose: () -> Unit) {
    val context = LocalActivity.current as? MainActivity
    var showAlert by rememberSaveable { mutableStateOf(false) }

    DrawerItem(
        icon = { Icon(Icons.Outlined.Autorenew, null) },
        label = { Text(stringResource(R.string.restart)) },
        onClick = {
            showAlert = true
            onClose()
        },
        enabled = stServiceRunning,
    )
    if (showAlert) {
        AlertDialog(
            onDismissRequest = { showAlert = false },
            title = { Text(stringResource(R.string.dialog_confirm_restart)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        context?.startService(
                            Intent(context, SyncthingService::class.java).apply {
                                action = SyncthingService.ACTION_RESTART
                            }
                        )
                        showAlert = false
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAlert = false },
                ) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun ExitDrawerItem() {
    val activity = LocalActivity.current as? MainActivity
    var showAlert by rememberSaveable { mutableStateOf(false) }

    DrawerItem(
        icon = { Icon(Icons.AutoMirrored.Outlined.ExitToApp, null) },
        label = { Text(stringResource(R.string.exit)) },
        onClick = {
            val isAutostartOn = activity?.preferences
                ?.getBoolean(Constants.PREF_START_SERVICE_ON_BOOT, false) == true
            if (isAutostartOn) {
                showAlert = true
            } else {
                activity?.doExit()
            }
        },
    )
    if (showAlert) {
        AlertDialog(
            onDismissRequest = { showAlert = false },
            title = { Text(stringResource(R.string.dialog_exit_while_running_as_service_title)) },
            text = { Text(stringResource(R.string.dialog_exit_while_running_as_service_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        activity?.doExit()
                    },
                ) {
                    Text(stringResource(R.string.yes))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showAlert = false },
                ) {
                    Text(stringResource(R.string.no))
                }
            },
        )
    }
}

// drawer item wrapper because NavigationDrawerItem doesn't support `enabled`
@Composable
private fun DrawerItem(
    icon: @Composable () -> Unit,
    label: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    if (enabled) {
        NavigationDrawerItem(
            icon = icon,
            label = label,
            onClick = onClick,
            selected = false,
            modifier = modifier
        )
    } else {
        val color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
        Surface(
            color = Color.Transparent,
            modifier = modifier
                .heightIn(min = 56.dp)
                .fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(start = 16.dp, end = 24.dp)
            ) {
                CompositionLocalProvider(LocalContentColor provides color, content = icon)
                Spacer(Modifier.width(12.dp))
                CompositionLocalProvider(LocalContentColor provides color, content = label)
            }
        }
    }
}

@Composable
private fun DrawerHeader() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .heightIn(min = 56.dp)
            .wrapContentWidth()
            .padding(start = 16.dp, end = 24.dp, bottom = 16.dp)
    ) {
        Image(
            painter = painterResource(R.drawable.ic_monochrome_ui),
            contentDescription = null,
            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
            modifier = Modifier.size(32.dp),
            contentScale = ContentScale.Fit
        )
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
