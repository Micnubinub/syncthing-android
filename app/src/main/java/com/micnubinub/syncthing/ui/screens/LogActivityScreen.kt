package com.micnubinub.syncthing.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.ui.viewModels.LogActivityAction
import com.micnubinub.syncthing.ui.viewModels.LogActivityState
import com.micnubinub.syncthing.ui.viewModels.LogActivityViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Preview(showSystemUi = true)
@Composable
fun LogActivityScreen(
    modifier: Modifier = Modifier,
    viewModel: LogActivityViewModel = LogActivityViewModel(),
    onShareLogFile: (File) -> Unit = {},
    onNavigateBack: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    val currentOnShareLogFile by rememberUpdatedState(onShareLogFile)
    LaunchedEffect(state.logFileToShare) {
        state.logFileToShare?.let { currentOnShareLogFile(it) }
    }

    LogActivityContent(
        state = state,
        onAction = viewModel::onAction,
        modifier = modifier,
        onNavigateBack = onNavigateBack
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogActivityContent(
    state: LogActivityState,
    onAction: (LogActivityAction) -> Unit,
    modifier: Modifier = Modifier,
    onNavigateBack: () -> Unit
) {
    val logContent = if (state.showSyncthingLog) state.syncthingLog else state.androidLog
    val logText = remember(logContent) { logContent }
    val scrollState = rememberScrollState()

    LaunchedEffect(logContent) {
        if (scrollState.value >= scrollState.maxValue - AUTO_SCROLL_THRESHOLD) {
            scrollState.scrollTo(scrollState.maxValue)
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(
                            if (state.showSyncthingLog) {
                                R.string.syncthing_log_title
                            } else {
                                R.string.android_log_title
                            }
                        )
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { onAction(LogActivityAction.ToggleShowSyncthingLog) }) {
                        Icon(
                            imageVector = Icons.Filled.SwapVert,
                            contentDescription = stringResource(
                                if (state.showSyncthingLog) {
                                    R.string.view_android_log
                                } else {
                                    R.string.view_syncthing_log
                                }
                            )
                        )
                    }
                    IconButton(onClick = { onAction(LogActivityAction.ShareLogFile) }) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = stringResource(R.string.share_title)
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            SelectionContainer(modifier = Modifier.fillMaxSize()) {
                key(logText) {
                    Text(
                        text = if (state.isLoading) {
                            stringResource(R.string.retrieving_logs)
                        } else {
                            logText
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(scrollState)
                            .padding(16.dp),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp
                    )
                }
            }
            if (state.isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            }
        }
    }
}

private const val AUTO_SCROLL_THRESHOLD = 200f
