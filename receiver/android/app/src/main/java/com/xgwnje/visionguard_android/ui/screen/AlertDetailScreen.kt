package com.xgwnje.visionguard_android.ui.screen

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ImageNotSupported
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.xgwnje.visionguard_android.service.AlertForegroundService
import com.xgwnje.visionguard_android.ui.home.buildAlertDetailChrome
import com.xgwnje.visionguard_android.ui.home.buildFrostedOverlaySpec
import com.xgwnje.visionguard_android.ui.theme.ReceiverBackground
import com.xgwnje.visionguard_android.ui.theme.ReceiverMuted
import com.xgwnje.visionguard_android.ui.theme.ReceiverPrimary
import com.xgwnje.visionguard_android.ui.theme.ReceiverSurface
import com.xgwnje.visionguard_android.ui.theme.ReceiverSurfaceMuted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun AlertDetailScreen(
    service: AlertForegroundService,
    alertId: String,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val alerts by service.alerts.collectAsState()
    val alert = alerts.find { it.alertId == alertId }
    val chrome = remember { buildAlertDetailChrome() }

    var screenshotBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var screenshotFailed by remember { mutableStateOf(false) }
    var saveMessage by remember { mutableStateOf<String?>(null) }

    fun saveCurrentScreenshot() {
        val bitmap = screenshotBitmap ?: return
        scope.launch {
            val saved = saveBitmapToGallery(context, bitmap)
            saveMessage = if (saved) "已保存到相册" else "保存失败"
        }
    }

    val legacyStoragePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            saveCurrentScreenshot()
        } else {
            saveMessage = "未获得相册权限"
        }
    }

    fun requestSaveToGallery() {
        if (!chrome.supportsGallerySave || screenshotBitmap == null) return
        if (
            Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            legacyStoragePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        saveCurrentScreenshot()
    }

    LaunchedEffect(alertId, alert?.screenshotUrl, alert?.hasScreenshot) {
        screenshotBitmap = null
        screenshotFailed = false
        if (alert == null) {
            screenshotFailed = true
            return@LaunchedEffect
        }

        val cachedFile = service.getScreenshotFile(alertId)
        if (cachedFile != null && cachedFile.exists()) {
            val bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(cachedFile.absolutePath)
            }
            if (bitmap != null) {
                screenshotBitmap = bitmap
                return@LaunchedEffect
            }
        }

        if (!alert.screenshotBase64.isNullOrEmpty()) {
            runCatching {
                val bytes = Base64.decode(alert.screenshotBase64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()?.let { bitmap ->
                screenshotBitmap = bitmap
                return@LaunchedEffect
            }
        }

        val downloadedFile = service.ensureScreenshotCached(alert)
        if (downloadedFile != null && downloadedFile.exists()) {
            val bitmap = withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(downloadedFile.absolutePath)
            }
            if (bitmap != null) {
                screenshotBitmap = bitmap
                return@LaunchedEffect
            }
        }

        screenshotFailed = true
    }

    LaunchedEffect(Unit) {
        service.onScreenshotData.collect { data ->
            if (data.alertId == alertId && data.imageBase64.isNotEmpty()) {
                runCatching {
                    val bytes = Base64.decode(data.imageBase64, Base64.DEFAULT)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }.getOrNull()?.let { bitmap ->
                    screenshotBitmap = bitmap
                    screenshotFailed = false
                }
            }
        }
    }

    LaunchedEffect(saveMessage) {
        if (saveMessage != null) {
            delay(1800)
            saveMessage = null
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(ReceiverBackground)
    ) {
        ScreenshotViewport(
            bitmap = screenshotBitmap,
            isLoading = alert != null && !screenshotFailed && screenshotBitmap == null,
            hasFailed = screenshotFailed
        )

        DetailTopControls(
            canSave = screenshotBitmap != null && chrome.supportsGallerySave,
            onBack = onBack,
            onSave = ::requestSaveToGallery,
            modifier = Modifier.align(Alignment.TopCenter)
        )

        saveMessage?.let { message ->
            DetailToast(
                message = message,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
private fun ScreenshotViewport(
    bitmap: Bitmap?,
    isLoading: Boolean,
    hasFailed: Boolean
) {
    var scale by remember(bitmap) { mutableStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        val nextScale = (scale * zoomChange).coerceIn(1f, 5f)
        scale = nextScale
        offset = if (nextScale == 1f) Offset.Zero else offset + panChange
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(bitmap) {
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offset = Offset.Zero
                        } else if (bitmap != null) {
                            scale = 2.4f
                        }
                    }
                )
            }
            .transformable(
                state = transformState,
                enabled = bitmap != null
            ),
        contentAlignment = Alignment.Center
    ) {
        when {
            bitmap != null -> {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "报警截图",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                )
            }
            isLoading -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(34.dp),
                    strokeWidth = 2.dp,
                    color = ReceiverPrimary
                )
            }
            hasFailed -> {
                EmptyScreenshotState()
            }
        }
    }
}

@Composable
private fun DetailTopControls(
    canSave: Boolean,
    onBack: () -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = statusPadding + 12.dp)
            .padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        DetailIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = "返回",
            enabled = true,
            onClick = onBack
        )
        Spacer(modifier = Modifier.width(18.dp))
        DetailIconButton(
            icon = Icons.Default.Download,
            contentDescription = "保存到相册",
            enabled = canSave,
            onClick = onSave
        )
    }
}

@Composable
private fun DetailIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit
) {
    val overlay = buildFrostedOverlaySpec()

    Surface(
        modifier = Modifier
            .size(52.dp)
            .alpha(if (enabled) 1f else 0.46f)
            .clickable(enabled = enabled, onClick = onClick),
        shape = RoundedCornerShape(22.dp),
        color = ReceiverSurface.copy(alpha = overlay.topBannerAlpha),
        border = BorderStroke(1.dp, Color.White.copy(alpha = overlay.borderAlpha)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = ReceiverPrimary,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

@Composable
private fun EmptyScreenshotState() {
    Surface(
        shape = RoundedCornerShape(30.dp),
        color = ReceiverSurface,
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.72f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.ImageNotSupported,
                contentDescription = null,
                tint = ReceiverMuted,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = "截图不可用",
                style = MaterialTheme.typography.labelLarge,
                color = ReceiverMuted
            )
        }
    }
}

@Composable
private fun DetailToast(
    message: String,
    modifier: Modifier = Modifier
) {
    val navigationPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Surface(
        modifier = modifier.padding(bottom = navigationPadding + 28.dp),
        shape = RoundedCornerShape(24.dp),
        color = ReceiverSurfaceMuted.copy(alpha = 0.94f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.72f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelLarge,
            color = ReceiverPrimary,
            fontWeight = FontWeight.Black,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)
        )
    }
}

private suspend fun saveBitmapToGallery(
    context: Context,
    bitmap: Bitmap
): Boolean = withContext(Dispatchers.IO) {
    val fileName = "VisionGuard_${System.currentTimeMillis()}.jpg"
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.DATE_ADDED, System.currentTimeMillis() / 1000)
        put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/VisionGuard")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
    }
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    val uri = resolver.insert(collection, values) ?: return@withContext false

    runCatching {
        resolver.openOutputStream(uri)?.use { output ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)) {
                error("Bitmap compression failed")
            }
        } ?: error("Gallery output stream is null")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        true
    }.getOrElse {
        resolver.delete(uri, null, null)
        false
    }
}
