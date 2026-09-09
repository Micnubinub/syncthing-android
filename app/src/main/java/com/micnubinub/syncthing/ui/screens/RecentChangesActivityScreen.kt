package com.micnubinub.syncthing.ui.screens

import android.net.Uri
import android.text.format.DateUtils
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Help
import androidx.compose.material.icons.automirrored.outlined.NoteAdd
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FolderDelete
import androidx.compose.material.icons.outlined.FolderSpecial
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.model.DiskEvent
import com.micnubinub.syncthing.ui.viewModels.RecentChangesActivityState
import com.micnubinub.syncthing.ui.viewModels.RecentChangesActivityViewModel
import com.micnubinub.syncthing.util.Util
import com.micnubinub.syncthing.util.isTelevision
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

@Preview(showSystemUi = true)
@Composable
fun RecentChangesActivityScreen(
    modifier: Modifier = Modifier,
    viewModel: RecentChangesActivityViewModel = RecentChangesActivityViewModel(),
    showExactTimes: Boolean = false,
    onNavigateBack: () -> Unit = {},
    onToggleExactTimes: () -> Unit = {},
    onItemClick: (DiskEvent) -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    RecentChangesActivityContent(
        state = state,
        showExactTimes = showExactTimes,
        onNavigateBack = onNavigateBack,
        onToggleExactTimes = onToggleExactTimes,
        onItemClick = onItemClick,
        modifier = modifier
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RecentChangesActivityContent(
    state: RecentChangesActivityState,
    showExactTimes: Boolean,
    onNavigateBack: () -> Unit,
    onToggleExactTimes: () -> Unit,
    onItemClick: (DiskEvent) -> Unit,
    modifier: Modifier = Modifier
) {
    val configuration = LocalConfiguration.current
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(
        snapAnimationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        flingAnimationSpec = rememberSplineBasedDecay()
    )

    Scaffold(
        modifier = if (configuration.isTelevision) {
            modifier
        } else {
            modifier.nestedScroll(scrollBehavior.nestedScrollConnection)
        },
        topBar = {
            if (configuration.isTelevision) {
                TopAppBar(
                    title = { Text(stringResource(R.string.recent_changes_title)) },
                    actions = {
                        OverflowMenu(
                            showExactTimes = showExactTimes,
                            onToggleExactTimes = onToggleExactTimes
                        )
                    }
                )
            } else {
                LargeTopAppBar(
                    title = { Text(stringResource(R.string.recent_changes_title)) },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.back)
                            )
                        }
                    },
                    actions = {
                        OverflowMenu(
                            showExactTimes = showExactTimes,
                            onToggleExactTimes = onToggleExactTimes
                        )
                    },
                    scrollBehavior = scrollBehavior
                )
            }
        }
    ) { paddingValues ->
        val contentModifier = modifier
            .fillMaxSize()
            .padding(paddingValues)

        when {
            state.isLoading -> Box(
                modifier = contentModifier,
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }

            state.items.isEmpty() -> Box(
                modifier = contentModifier,
                contentAlignment = Alignment.Center
            ) {
                Text(text = stringResource(R.string.no_recent_changes))
            }

            else -> LazyColumn(modifier = contentModifier) {
                items(items = state.items, key = { it.id }) { diskEvent ->
                    RecentChangeItem(
                        diskEvent = diskEvent,
                        showExactTimes = showExactTimes,
                        onClick = { onItemClick(diskEvent) }
                    )
                }
            }
        }
    }
}

@Composable
private fun OverflowMenu(
    showExactTimes: Boolean,
    onToggleExactTimes: () -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }

    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(
                imageVector = Icons.Outlined.MoreVert,
                contentDescription = stringResource(R.string.more_options)
            )
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.show_exact_times)) },
                onClick = {
                    onToggleExactTimes()
                    expanded = false
                },
                leadingIcon = {
                    if (showExactTimes) {
                        Icon(Icons.Outlined.Check, contentDescription = null)
                    } else {
                        Spacer(Modifier.size(24.dp))
                    }
                }
            )
        }
    }
}

@Composable
private fun RecentChangeItem(
    diskEvent: DiskEvent,
    showExactTimes: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val encodedPath = diskEvent.data.path.replace("#", Uri.encode("#"))
    val filename = encodedPath.toUri().lastPathSegment ?: diskEvent.data.path
    val folderPath = diskEvent.data.path.substringBeforeLast('/', "")
    val timeText = timeText(diskEvent.time, showExactTimes)

    Row(
        modifier = modifier
            .padding(6.dp)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.Top
    ) {
        Icon(
            typeIconId(diskEvent),
            contentDescription = stringResource(R.string.generic_help),
            modifier = Modifier.padding(end = 6.dp)
        )
        Column {
            Text(
                text = filename,
                fontSize = 16.sp
            )
            Text(
                text = "[" + diskEvent.data.label + "]" + File.separator + folderPath,
                fontSize = 12.sp
            )
            Text(
                text = stringResource(R.string.modified_by_device, diskEvent.data.modifiedBy),
                fontSize = 12.sp
            )
            timeText?.let {
                Text(
                    text = stringResource(
                        R.string.modification_time,
                        it
                    ),
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun timeText(time: String, showExactTimes: Boolean): String? {
    // SimpleDateFormat is not thread-safe; this only ever runs on the main dispatcher.
    val timestampParser = remember { SimpleDateFormat(RFC3339_PATTERN, Locale.US) }
    val epochMillis = remember(time) { timestampParser.parseEpochMillisOrNull(time) }
    return when (showExactTimes) {
        true -> Util.formatDateTime(time)
        false -> epochMillis?.let {
            DateUtils.getRelativeTimeSpanString(it).toString()
        } ?: Util.formatDateTime(time)
    }
}

private const val RFC3339_PATTERN = "yyyy-MM-dd'T'HH:mm:ssZ"

/**
 * Parses a Syncthing timestamp (RFC 3339, e.g. `2018-10-29T15:18:52.6183215+01:00`) to epoch millis,
 * or null if it does not parse.
 *
 * `SimpleDateFormat` is used rather than `java.time` because no core library desugaring is enabled,
 * so `Instant`/`ZonedDateTime` are unavailable below API 26. It needs the input normalised first:
 * the fractional seconds dropped (it cannot handle 7-9 digits), and the offset reduced from `+01:00`
 * to the RFC 822 `+0100` that the `Z` pattern expects.
 */
private fun SimpleDateFormat.parseEpochMillisOrNull(raw: String): Long? {
    if (raw.isEmpty()) return null
    val normalised = raw
        .replace(FRACTIONAL_SECONDS, "")
        .let { if (it.endsWith("Z")) it.dropLast(1) + "+0000" else it }
        .replace(OFFSET_WITH_COLON, "$1$2")
    return runCatching { parse(normalised)?.time }.getOrNull()
}

private val FRACTIONAL_SECONDS = Regex("\\.\\d+")
private val OFFSET_WITH_COLON = Regex("([+-]\\d{2}):(\\d{2})$")

private fun typeIconId(diskEvent: DiskEvent): ImageVector = when (diskEvent.data.type) {
    "dir" -> when (diskEvent.data.action) {
        "added" -> Icons.Default.CreateNewFolder
        "deleted" -> Icons.Outlined.FolderDelete
        "modified" -> Icons.Outlined.FolderSpecial
        else -> Icons.AutoMirrored.Outlined.Help
    }

    "file" -> when (diskEvent.data.action) {
        "added" -> Icons.AutoMirrored.Outlined.NoteAdd
        "deleted" -> Icons.Outlined.Delete
        "modified" -> Icons.Outlined.Edit
        else -> Icons.AutoMirrored.Outlined.Help
    }

    else -> Icons.AutoMirrored.Outlined.Help
}
