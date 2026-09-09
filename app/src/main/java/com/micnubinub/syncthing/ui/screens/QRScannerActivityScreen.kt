package com.micnubinub.syncthing.ui.screens

import android.Manifest
import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.PermissionStatus
import com.google.accompanist.permissions.rememberPermissionState
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.micnubinub.syncthing.R
import com.micnubinub.syncthing.ui.viewModels.QRScannerActivityState
import com.micnubinub.syncthing.ui.viewModels.QRScannerActivityViewModel
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import androidx.camera.core.Preview as CameraPreview

@Preview(showSystemUi = true)
@Composable
fun QRScannerActivityScreen(
    modifier: Modifier = Modifier,
    viewModel: QRScannerActivityViewModel = QRScannerActivityViewModel(),
    onBarcodeResult: (String) -> Unit = {},
    onCancel: () -> Unit = {}
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    QRScannerActivityContent(
        state = state,
        analysisExecutor = viewModel.analysisExecutor,
        modifier = modifier,
        onBarcodeResult = onBarcodeResult,
        onCancel = onCancel
    )
}

@OptIn(ExperimentalPermissionsApi::class)
@Composable
private fun QRScannerActivityContent(
    state: QRScannerActivityState,
    analysisExecutor: Executor,
    onBarcodeResult: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionState = rememberPermissionState(Manifest.permission.CAMERA) { isGranted ->
        if (!isGranted) {
            onCancel()
        }
    }

    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            cameraProviderRef?.unbindAll()
        }
    }

    var requestedPermission by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(permissionState.status) {
        if (permissionState.status !is PermissionStatus.Granted && !requestedPermission) {
            requestedPermission = true
            permissionState.launchPermissionRequest()
        }
    }

    LaunchedEffect(permissionState.status, previewView) {
        val view = previewView ?: return@LaunchedEffect
        if (permissionState.status is PermissionStatus.Granted) {
            val cameraProvider = context.awaitCameraProvider()
            cameraProviderRef = cameraProvider

            val preview = CameraPreview.Builder().build().also {
                it.surfaceProvider = view.surfaceProvider
            }
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(
                        analysisExecutor, QrCodeAnalyzer(
                            mainExecutor = ContextCompat.getMainExecutor(context),
                            onResult = onBarcodeResult
                        )
                    )
                }

            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis
            )
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        AndroidView(
            factory = { context ->
                PreviewView(context).apply {
                    previewView = this
                }
            },
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        )
        Text(
            text = stringResource(R.string.cancel_title),
            modifier = Modifier
                .fillMaxWidth()
                .height(dimensionResource(R.dimen.qr_cancel_bar_height))
                .background(MaterialTheme.colorScheme.background)
                .clickable(onClick = onCancel),
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = LocalDensity.current.run {
                dimensionResource(R.dimen.qr_cancel_font_size).toSp()
            }
        )
    }
}

private class QrCodeAnalyzer(
    private val mainExecutor: java.util.concurrent.Executor,
    private val onResult: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val reader = MultiFormatReader().apply {
        setHints(
            mapOf(DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE))
        )
    }
    private val found = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        try {
            if (found.get()) {
                return
            }
            val result = runCatching {
                reader.decodeWithState(BinaryBitmap(HybridBinarizer(image.toLuminanceSource())))
            }.getOrNull()
            if (result != null && found.compareAndSet(false, true)) {
                mainExecutor.execute { onResult(result.text) }
            }
        } finally {
            image.close()
        }
    }

    private fun ImageProxy.toLuminanceSource(): PlanarYUVLuminanceSource {
        val yPlane = planes[0]
        val yBuffer = yPlane.buffer
        val data = ByteArray(width * height)
        if (yPlane.rowStride == width && yPlane.pixelStride == 1) {
            yBuffer.get(data)
        } else {
            val rowStride = yPlane.rowStride
            val pixelStride = yPlane.pixelStride
            for (row in 0 until height) {
                val rowStart = row * rowStride
                val outStart = row * width
                for (col in 0 until width) {
                    data[outStart + col] = yBuffer.get(rowStart + col * pixelStride)
                }
            }
        }
        return PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
    }
}

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCancellableCoroutine { continuation ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            {
                if (!continuation.isCancelled) {
                    try {
                        continuation.resume(future.get())
                    } catch (e: Exception) {
                        continuation.resumeWithException(e)
                    }
                }
            },
            ContextCompat.getMainExecutor(this)
        )
    }
