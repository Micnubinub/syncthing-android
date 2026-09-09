package com.micnubinub.syncthing.ui.screens.fragments

import android.content.ClipData
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.theme.ApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun DeviceIdDialog(
    deviceName: String,
    deviceId: String,
    isCurrentDevice: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val clipboardManager = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val deviceIdCopiedToast = stringResource(R.string.device_id_copied_to_clipboard)
    val shareDeviceIdChooser = stringResource(R.string.share_device_id_chooser)
    val qrCodeState = produceState<Bitmap?>(null, deviceId) {
        value = withContext(Dispatchers.Default) {
            generateQrCode(deviceId)
        }
    }
    val qrCode = qrCodeState.value

    val onCopy = {
        scope.launch {
            clipboardManager.setClipEntry(
                ClipEntry(
                    ClipData.newPlainText(
                        AnnotatedString(deviceId).text,
                        AnnotatedString(deviceId).text
                    )
                )
            )
        }
        // Android 13+ shows a system confirmation automatically
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(
                context,
                deviceIdCopiedToast,
                Toast.LENGTH_SHORT
            ).show()
        }
    }
    val onShare = {
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, deviceId)
        }
        context.startActivity(
            Intent.createChooser(
                shareIntent,
                shareDeviceIdChooser
            )
        )
    }

    ApplicationTheme {
        AlertDialog(
            properties = DialogProperties(usePlatformDefaultWidth = false),
            modifier = Modifier.let {
                if (!isLandscape()) it
                    .widthIn(max = 460.dp)
                    .fillMaxWidth(0.9f) else it
            },
            onDismissRequest = onDismiss,
            title = {
                if (isLandscape()) {
                    Row(Modifier.fillMaxWidth()) {
                        DialogTitle(deviceName, isCurrentDevice, Modifier.weight(1f))
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Outlined.Close, stringResource(R.string.close_device_id))
                        }
                    }
                } else {
                    DialogTitle(deviceName, isCurrentDevice)
                }
            },
            text = {
                if (qrCode != null) {
                    if (isLandscape()) {
                        LandscapeDialogContent(deviceId, qrCode, onCopy, onShare)
                    } else {
                        PortraitDialogContent(deviceId, qrCode, onCopy, onShare)
                    }
                }
            },
            confirmButton = {
                if (!isLandscape()) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.finish))
                    }
                }
            },
        )
    }
}

private fun generateQrCode(deviceId: String): Bitmap {
    val qrSize = 232
    val black = 0xFF000000
    val white = 0x00000000

    val bitMatrix = MultiFormatWriter()
        .encode(deviceId, BarcodeFormat.QR_CODE, qrSize, qrSize)
    val bitMap = createBitmap(bitMatrix.width, bitMatrix.height, Bitmap.Config.ARGB_8888)

    for (x in 0 until qrSize) {
        for (y in 0 until qrSize) {
            val pixel = if (bitMatrix[x, y]) black else white
            bitMap[x, y] = pixel.toInt()
        }
    }

    return bitMap
}

@Composable
private fun isLandscape(): Boolean {
    val configuration = LocalConfiguration.current
    return configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
}

@Composable
private fun DialogTitle(
    deviceName: String,
    isCurrentDevice: Boolean,
    modifier: Modifier = Modifier
) {
    val thisDeviceText = stringResource(R.string.this_device)

    Column(modifier) {
        Text(stringResource(R.string.device_id))
        Spacer(Modifier.height(4.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = deviceName
                    if (isCurrentDevice) {
                        stateDescription = thisDeviceText
                    }
                }
        ) {
            Text(
                text = deviceName,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.tertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (isCurrentDevice) {
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "•",
                    style = MaterialTheme.typography.titleMedium,
                    softWrap = false,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = thisDeviceText,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.tertiary,
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun PortraitDialogContent(
    deviceId: String,
    qrCode: Bitmap,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    val windowInfo = LocalWindowInfo.current
    val containerHeightDp = windowInfo.containerDpSize.height
    val qrMaxHeight = minOf(containerHeightDp * 0.30f, 280.dp)

    Column {
        QrCodeSurface(
            qrCode, Modifier
                .heightIn(min = 160.dp, max = qrMaxHeight)
                .fillMaxWidth()
        )
        Spacer(Modifier.height(16.dp))
        DeviceIdCard(deviceId)
        Spacer(Modifier.height(16.dp))
        DeviceIdActions(onCopy, onShare)
    }
}

@Composable
private fun LandscapeDialogContent(
    deviceId: String,
    qrCode: Bitmap,
    onCopy: () -> Unit,
    onShare: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 250.dp)
    ) {
        QrCodeSurface(
            qrCode, Modifier
                .fillMaxHeight()
                .weight(1f)
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            DeviceIdCard(deviceId)
            Spacer(Modifier.height(12.dp))
            DeviceIdActions(onCopy, onShare)
        }
    }
}

@Composable
private fun QrCodeSurface(
    qrCode: Bitmap,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        modifier = modifier
    ) {
        Image(
            bitmap = qrCode.asImageBitmap(),
            contentDescription = stringResource(R.string.device_id),
            modifier = Modifier.fillMaxHeight()
        )
    }
}

@Composable
private fun DeviceIdCard(deviceId: String) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Text(deviceId, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun DeviceIdActions(
    onCopy: () -> Unit,
    onShare: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        FilledTonalButton(onCopy, Modifier.weight(1f)) {
            Icon(
                imageVector = Icons.Outlined.ContentCopy,
                contentDescription = stringResource(R.string.copy)
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.copy))
        }
        FilledTonalButton(onShare, Modifier.weight(1f)) {
            Icon(
                imageVector = Icons.Outlined.Share,
                contentDescription = stringResource(R.string.share_title)
            )
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.share_title))
        }
    }
}
