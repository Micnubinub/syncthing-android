package com.micnubinub.syncthing.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micnubinub.syncthing.ui.viewModels.SyncthingActivityState
import com.micnubinub.syncthing.ui.viewModels.SyncthingActivityViewModel

@Preview(showSystemUi = true)
@Composable
fun SyncthingActivityScreen(
    modifier: Modifier = Modifier,
    viewModel: SyncthingActivityViewModel = SyncthingActivityViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    SyncthingActivityContent(
        state = state,
        modifier = modifier,
        viewModel = viewModel
    )
}

@Composable
private fun SyncthingActivityContent(
    state: SyncthingActivityState,
    viewModel: SyncthingActivityViewModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize()
    ) {


    }
}
