package com.micnubinub.syncthing.ui.screens.fragments

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.micnubinub.syncthing.R

/**
 * Shown when no file versioning type is selected.
 */
@Composable
fun NoVersioningScreen(modifier: Modifier = Modifier) {
    DescriptionText(stringResource(R.string.no_versioning_description), modifier = modifier)
}
