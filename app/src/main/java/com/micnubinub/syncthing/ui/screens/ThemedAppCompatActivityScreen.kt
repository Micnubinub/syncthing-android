package com.micnubinub.syncthing.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micnubinub.syncthing.ui.viewModels.ThemedAppCompatActivityState
import com.micnubinub.syncthing.ui.viewModels.ThemedAppCompatActivityViewModel

@Preview(showSystemUi = true)
@Composable
fun ThemedAppCompatActivityScreen(
    modifier: Modifier = Modifier,
    viewModel: ThemedAppCompatActivityViewModel = ThemedAppCompatActivityViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    ThemedAppCompatActivityContent(
        state = state,
        modifier = modifier,
        viewModel = viewModel
    )
}

@Composable
private fun ThemedAppCompatActivityContent(
    state: ThemedAppCompatActivityState,
    viewModel: ThemedAppCompatActivityViewModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxSize()
    ) {


    }
}
