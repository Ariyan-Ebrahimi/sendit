package com.beamdrop.app.ui.screens

import com.beamdrop.app.i18n.tr
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.beamdrop.app.BeamDropViewModel
import com.beamdrop.app.ui.components.BeamButton
import com.beamdrop.app.ui.components.BeamButtonStyle
import com.beamdrop.app.ui.components.BeamIconButton
import com.beamdrop.app.ui.components.BeamTextField
import com.beamdrop.app.ui.components.EmptyState
import com.beamdrop.app.ui.components.Pill
import com.beamdrop.app.ui.theme.Ink
import com.beamdrop.app.ui.theme.InkDim
import com.beamdrop.app.ui.theme.OnSurface
import com.beamdrop.app.ui.theme.OnSurfaceVariant
import com.beamdrop.app.ui.theme.Secondary
import com.beamdrop.app.ui.theme.SurfaceContainer
import com.beamdrop.app.ui.theme.SurfaceHigh
import com.beamdrop.app.ui.theme.Tertiary
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun ScannerScreen(
    viewModel: BeamDropViewModel,
    onBack: () -> Unit,
    onUrlReady: (String) -> Unit
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    var torchOn by remember { mutableStateOf(false) }
    var hasFlash by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (!hasPermission) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Ink)
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BeamIconButton(
                    icon = Icons.Rounded.ArrowBack,
                    contentDescription = tr("Back"),
                    onClick = onBack,
                    background = Color.Transparent
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = tr("Scan QR"),
                    style = MaterialTheme.typography.titleLarge,
                    color = OnSurface
                )
            }
            EmptyState(
                icon = Icons.Rounded.QrCodeScanner,
                title = tr("Camera permission needed"),
                message = tr("Sendit uses the camera only to read the sender's QR code. Nothing is recorded or uploaded."),
                actionText = tr("Allow camera"),
                onAction = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.weight(1f)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                BeamButton(
                    text = tr("Open app settings"),
                    onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${context.packageName}")
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    style = BeamButtonStyle.Ghost
                )
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(InkDim)) {
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            torchOn = torchOn,
            onCamera = { bound ->
                camera = bound
                hasFlash = bound?.cameraInfo?.hasFlashUnit() == true
            },
            onCode = { code ->
                onUrlReady(code)
            }
        )

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to InkDim.copy(alpha = 0.86f),
                        0.24f to Color.Transparent,
                        0.62f to Color.Transparent,
                        1f to InkDim.copy(alpha = 0.94f)
                    )
                )
        )

        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BeamIconButton(
                icon = Icons.Rounded.ArrowBack,
                contentDescription = tr("Back"),
                onClick = onBack,
                background = InkDim.copy(alpha = 0.55f)
            )
            Spacer(Modifier.width(6.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = tr("Scan QR"),
                    style = MaterialTheme.typography.titleLarge,
                    color = OnSurface
                )
                Text(
                    text = tr("Point at the sender's QR code"),
                    style = MaterialTheme.typography.bodySmall,
                    color = OnSurfaceVariant
                )
            }
            if (hasFlash) {
                BeamIconButton(
                    icon = if (torchOn) Icons.Rounded.FlashOff else Icons.Rounded.FlashOn,
                    contentDescription = if (torchOn) tr("Turn off flashlight") else tr("Turn on flashlight"),
                    onClick = {
                        torchOn = !torchOn
                        runCatching { camera?.cameraControl?.enableTorch(torchOn) }
                    },
                    background = InkDim.copy(alpha = 0.55f),
                    tint = if (torchOn) Secondary else OnSurface
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Box(modifier = Modifier.size(268.dp)) {
                ScanFrame(modifier = Modifier.fillMaxSize())
            }
        }

    }
}

@Composable
private fun ScanFrame(modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "scanLaser")
    val line by transition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(1900, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laser"
    )
    Canvas(modifier = modifier) {
        val corner = 34.dp.toPx()
        val stroke = 3.dp.toPx()
        val inset = 3.dp.toPx()
        val color = Secondary
        val width = size.width
        val height = size.height

        fun cornerLines(x: Float, y: Float, dx: Float, dy: Float) {
            drawLine(
                color = color,
                start = Offset(x, y),
                end = Offset(x + dx * corner, y),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            drawLine(
                color = color,
                start = Offset(x, y),
                end = Offset(x, y + dy * corner),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }

        cornerLines(inset, inset, 1f, 1f)
        cornerLines(width - inset, inset, -1f, 1f)
        cornerLines(inset, height - inset, 1f, -1f)
        cornerLines(width - inset, height - inset, -1f, -1f)

        val y = (height * line).coerceIn(8.dp.toPx(), height - 8.dp.toPx())
        drawLine(
            brush = Brush.horizontalGradient(
                colors = listOf(Color.Transparent, Secondary, Color.Transparent)
            ),
            start = Offset(10.dp.toPx(), y),
            end = Offset(width - 10.dp.toPx(), y),
            strokeWidth = 2.dp.toPx(),
            cap = StrokeCap.Round
        )
        drawCircle(
            color = Secondary.copy(alpha = 0.85f),
            radius = 2.5.dp.toPx(),
            center = Offset(width / 2f, y)
        )
    }
}

@Composable
private fun CameraPreview(
    modifier: Modifier,
    torchOn: Boolean,
    onCamera: (Camera?) -> Unit,
    onCode: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val executor = remember { Executors.newSingleThreadExecutor() }
    val found = remember { AtomicBoolean(false) }
    val currentOnCode by rememberUpdatedState(onCode)
    val currentOnCamera by rememberUpdatedState(onCamera)
    val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
    val reader = remember {
        MultiFormatReader().apply {
            setHints(
                mapOf(
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true
                )
            )
        }
    }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var camera by remember { mutableStateOf<Camera?>(null) }

    AndroidView(
        factory = { previewView },
        modifier = modifier
    )

    LaunchedEffect(Unit) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            { provider = runCatching { future.get() }.getOrNull() },
            mainExecutor
        )
    }

    LaunchedEffect(provider) {
        val cameraProvider = provider ?: return@LaunchedEffect
        val preview = Preview.Builder()
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setTargetResolution(android.util.Size(1280, 720))
            .build()
        analysis.setAnalyzer(executor) { proxy ->
            analyzeFrame(proxy, reader, found, mainExecutor, currentOnCode)
        }
        runCatching {
            cameraProvider.unbindAll()
            val bound = cameraProvider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis
            )
            camera = bound
            currentOnCamera(bound)
        }.onFailure {
            currentOnCamera(null)
        }
    }

    LaunchedEffect(torchOn, camera) {
        runCatching { camera?.cameraControl?.enableTorch(torchOn) }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { camera?.cameraControl?.enableTorch(false) }
            runCatching { provider?.unbindAll() }
            executor.shutdown()
        }
    }
}

@androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
private fun analyzeFrame(
    proxy: ImageProxy,
    reader: MultiFormatReader,
    found: AtomicBoolean,
    mainExecutor: Executor,
    onCode: (String) -> Unit
) {
    if (found.get()) {
        proxy.close()
        return
    }
    val image = proxy.image
    if (image == null) {
        proxy.close()
        return
    }
    try {
        val plane = image.planes.firstOrNull()
        if (plane == null) {
            proxy.close()
            return
        }
        val buffer = plane.buffer
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        val source = PlanarYUVLuminanceSource(
            data,
            plane.rowStride,
            image.height,
            0,
            0,
            image.width,
            image.height,
            false
        )
        val bitmap = BinaryBitmap(HybridBinarizer(source))
        val result = reader.decodeWithState(bitmap)
        val text = result?.text
        if (!text.isNullOrBlank() && found.compareAndSet(false, true)) {
            mainExecutor.execute { onCode(text) }
        }
    } catch (_: NotFoundException) {
        // No QR in this frame; keep scanning.
    } catch (_: Exception) {
        // A malformed frame or an unusual stride should not stop the camera.
    } finally {
        reader.reset()
        proxy.close()
    }
}
