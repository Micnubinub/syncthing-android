package com.micnubinub.syncthing.ui.components

import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview

@Preview(showBackground = true)
@Composable
internal fun UISwitch(
    checked: Boolean?,
    title: String = "",
    righttItem: @Composable (() -> Unit)?,
) {
    ThreeSlotRow(
        title = title,
        righttItem = {
            Switch(
                checked ?: true,
                onCheckedChange = {

                }
            )
        }
    )
}