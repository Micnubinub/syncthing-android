package com.micnubinub.syncthing.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Preview(showBackground = true)
@Composable
internal fun ThreeSlotRow(
    leftItem: @Composable (() -> Unit)? = null,
    title: String = "",
    righttItem: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = {
            leftItem?.let {
                leftItem()
            }
            Text(
                title,
                fontSize = 18.sp,
                modifier = Modifier
                    .weight(1f)
                    .padding(2.dp)
            )
            righttItem?.let {
                righttItem()
            }
        }
    )
}