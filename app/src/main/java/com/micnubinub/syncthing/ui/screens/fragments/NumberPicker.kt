package com.micnubinub.syncthing.ui.screens.fragments

import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlin.math.abs

private val PickerItemHeight = 36.dp
private const val PickerVisibleItemCount = 3
private val PickerHeight = PickerItemHeight * PickerVisibleItemCount

/**
 * Simply displays a wheel-style number picker that allows selecting a value from [range].
 */
@Composable
fun NumberPicker(
    value: Int,
    range: IntRange,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val itemCount = range.last - range.first + 1
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = (value - range.first).coerceIn(0, itemCount - 1)
    )

    val selectedIndex by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val viewportCenter = (layoutInfo.viewportStartOffset + layoutInfo.viewportEndOffset) / 2
            layoutInfo.visibleItemsInfo
                .minByOrNull { abs(it.offset + it.size / 2 - viewportCenter) }
                ?.index
                ?: (value - range.first).coerceIn(0, itemCount - 1)
        }
    }

    // Report the value the wheel settles on.
    LaunchedEffect(listState.isScrollInProgress) {
        if (!listState.isScrollInProgress) {
            val newValue = range.first + selectedIndex
            if (newValue != value) {
                onValueChange(newValue)
            }
        }
    }

    // Keep the wheel in sync when the value changes externally.
    LaunchedEffect(value) {
        val index = (value - range.first).coerceIn(0, itemCount - 1)
        if (!listState.isScrollInProgress && index != selectedIndex) {
            listState.scrollToItem(index)
        }
    }

    Box(modifier = modifier.height(PickerHeight)) {
        LazyColumn(
            state = listState,
            flingBehavior = rememberSnapFlingBehavior(lazyListState = listState),
            contentPadding = PaddingValues(vertical = (PickerHeight - PickerItemHeight) / 2),
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxSize()
        ) {
            items(count = itemCount) { index ->
                Text(
                    text = (range.first + index).toString(),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (index == selectedIndex) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    maxLines = 1,
                    modifier = Modifier
                        .height(PickerItemHeight)
                        .wrapContentHeight(Alignment.CenterVertically)
                )
            }
        }
        HorizontalDivider(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = -PickerItemHeight / 2)
                .padding(horizontal = 64.dp)
        )
        HorizontalDivider(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = PickerItemHeight / 2)
                .padding(horizontal = 64.dp)
        )
    }
}
