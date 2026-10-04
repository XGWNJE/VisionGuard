package com.xgwnje.visionguard.notifier.ui.settings

import com.xgwnje.visionguard.icons.LucideIcons

import android.app.Application
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import com.xgwnje.visionguard.account.VisionGuardControlColors
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xgwnje.visionguard.account.VisionGuardStatusColors
import com.xgwnje.visionguard.notifier.PermissionUtils
import com.xgwnje.visionguard.notifier.RingtoneLibrary
import com.xgwnje.visionguard.notifier.ui.*
import com.xgwnje.visionguard.notifier.ui.dialogs.PermissionGuideDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private data class RingtoneFeedback(override val message: String, val isError: Boolean) : SnackbarVisuals {
    override val actionLabel: String? = null
    override val withDismissAction: Boolean = false
    override val duration: SnackbarDuration = SnackbarDuration.Long
}

/** 内置铃声、导入音频与录音；预设仅试听，自定义条目可重命名和删除。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun RingtoneLibraryScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel(
        factory = SettingsViewModelFactory(LocalContext.current.applicationContext as Application)
    )
) {
    val context = LocalContext.current
    val entries = viewModel.ringtoneLibrary
    @Suppress("UNUSED_VARIABLE") val previewVersion = viewModel.previewVersion.value
    @Suppress("UNUSED_VARIABLE") val recordingVersion = viewModel.recordingVersion.value
    val previewing = viewModel.previewingFileName
    val isRecording = viewModel.isRecording
    var showMicGuide by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleteTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val reportFeedback: (String, Boolean) -> Unit = { text, isError ->
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(RingtoneFeedback(text, isError))
        }
    }
    var importing by remember { mutableStateOf(false) }
    var recordSeconds by remember { mutableLongStateOf(0L) }

    LaunchedEffect(isRecording) {
        recordSeconds = 0L
        while (isRecording) { delay(1000); recordSeconds++ }
    }
    DisposableEffect(Unit) {
        onDispose { RingtoneLibrary.stopPreview(); RingtoneLibrary.cancelRecording() }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            viewModel.importRingtone(uri) { ok ->
                importing = false
                reportFeedback(if (ok) "已导入铃声" else "导入失败，请选择可读取的音频文件", !ok)
            }
        }
    }
    val micPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (!viewModel.startRecording()) {
                reportFeedback("录音启动失败，请检查麦克风权限后重试", true)
            }
        } else showMicGuide = true
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).navigationBarsPadding()) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp)
        ) {
            item(key = "page_header") {
                NotifierPageHeader("铃声库", onNavigateBack) {
                    TextButton(
                        onClick = {
                            runCatching { importLauncher.launch(arrayOf("audio/*")) }.onFailure {
                                reportFeedback("文件选择不可用，请检查系统文件应用后重试", true)
                            }
                        },
                        enabled = !importing,
                        modifier = Modifier.heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.small,
                        colors = VisionGuardControlColors.textButton(contentColor = VisionGuardStatusColors.onSuccessContainer)
                    ) { Text(if (importing) "导入中" else "导入") }
                }
            }
            item(key = "recording") {
                NotifierPanel {
                    Text("录制铃声", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (isRecording) "正在录音 · ${recordSeconds} 秒" else "使用麦克风录音，停止后保存到铃声库。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            if (isRecording) {
                                val saved = viewModel.stopRecording()
                                reportFeedback(if (saved) "录音已保存" else "录音太短或失败，未保存", !saved)
                            } else if (PermissionUtils.isRecordAudioGranted(context)) {
                                if (!viewModel.startRecording()) {
                                    reportFeedback("录音启动失败，请检查麦克风权限后重试", true)
                                }
                            } else micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        shape = MaterialTheme.shapes.small,
                        colors = if (isRecording) VisionGuardControlColors.button(containerColor = MaterialTheme.colorScheme.error, contentColor = MaterialTheme.colorScheme.onError) else VisionGuardControlColors.button()
                    ) { Text(if (isRecording) "停止并保存" else "开始录音") }
                }
            }
            if (importing) item(key = "importing") {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().semantics { contentDescription = "正在导入铃声" })
            }
            item(key = "preset_heading") {
                Text("内置铃声 · ${RingtoneLibrary.PRESETS.size} 首", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }
            items(RingtoneLibrary.PRESETS, key = { "preset:${it.rawName}" }) { preset ->
                PresetEntryRow(preset, previewing == "preset:${preset.rawName}") {
                    val wasPreviewing = viewModel.previewingFileName == "preset:${preset.rawName}"
                    viewModel.togglePresetPreview(preset)
                    if (!wasPreviewing && viewModel.previewingFileName != "preset:${preset.rawName}") {
                        reportFeedback("预设铃声试听失败，请重试", true)
                    }
                }
            }
            item(key = "library_heading") {
                Text("我的铃声 · ${entries.size} 首", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }
            if (entries.isEmpty()) item(key = "empty_hint") {
                NotifierPanel { NotifierEmptyState("还没有自定义铃声", "点击右上角导入音频，或录制一段铃声。") }
            }
            items(entries, key = { it.first }) { (fileName, displayName) ->
                RingtoneEntryRow(
                    displayName = displayName,
                    isPreviewing = previewing == fileName,
                    onTogglePreview = {
                        val wasPreviewing = viewModel.previewingFileName == fileName
                        viewModel.togglePreview(fileName)
                        if (!wasPreviewing && viewModel.previewingFileName != fileName) {
                            reportFeedback("试听失败，请重新导入可播放的音频", true)
                        }
                    },
                    onRename = { renameTarget = fileName to displayName },
                    onDelete = { deleteError = null; deleteTarget = fileName to displayName },
                    modifier = Modifier.animateItemPlacement()
                )
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(16.dp)) { data ->
            val isError = (data.visuals as? RingtoneFeedback)?.isError == true
            Snackbar(
                snackbarData = data,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                shape = MaterialTheme.shapes.small,
                containerColor = if (isError) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.inverseSurface,
                contentColor = if (isError) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.inverseOnSurface
            )
        }
    }

    if (showMicGuide) PermissionGuideDialog(
        title = "需要麦克风权限",
        message = "录音功能需要麦克风权限。请在系统设置中允许 VisionGuard 使用麦克风。",
        onConfirm = {
            showMicGuide = false
            (context as? android.app.Activity)?.let { PermissionUtils.openAppDetailsSettings(it) }
        },
        onDismiss = { showMicGuide = false }
    )
    renameTarget?.let { (fileName, oldName) ->
        RenameRingtoneDialog(
            oldName = oldName,
            onConfirm = { newName -> viewModel.renameLibraryRingtone(fileName, newName); renameTarget = null },
            onDismiss = { renameTarget = null }
        )
    }
    deleteTarget?.let { (fileName, displayName) ->
        NotifierDialog(
            title = "删除铃声",
            onDismiss = { deleteTarget = null },
            focusOnDismiss = true,
            confirmButton = {
                TextButton(
                    onClick = {
                        if (viewModel.deleteLibraryRingtone(fileName)) {
                            deleteTarget = null
                            reportFeedback("已删除铃声", false)
                        } else deleteError = "删除失败，铃声已保留，请重试"
                    },
                    modifier = Modifier.heightIn(min = 48.dp).semantics { traversalIndex = 1f },
                    shape = MaterialTheme.shapes.small,
                    colors = VisionGuardControlColors.textButton(contentColor = MaterialTheme.colorScheme.error)
                ) { Text("删除") }
            },
            dismissButton = { TextButton({ deleteTarget = null }, Modifier.heightIn(min = 48.dp).semantics { traversalIndex = 0f }, shape = MaterialTheme.shapes.small) { Text("取消") } }
        ) {
            Text("确认删除「$displayName」？使用它的报警将回落到默认闹钟铃声。", style = MaterialTheme.typography.bodyLarge)
            deleteError?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun PresetEntryRow(preset: RingtoneLibrary.PresetRingtone, isPreviewing: Boolean, onTogglePreview: () -> Unit) {
    NotifierPanel {
        Text(preset.displayName, style = MaterialTheme.typography.titleSmall)
        PreviewButton(isPreviewing, onTogglePreview)
    }
}

@Composable
private fun RingtoneEntryRow(
    displayName: String,
    isPreviewing: Boolean,
    onTogglePreview: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    NotifierPanel(modifier) {
        Text(displayName, style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PreviewButton(isPreviewing, onTogglePreview, Modifier.weight(1f))
            IconButton(onRename, Modifier.size(48.dp)) { Icon(LucideIcons.Pencil, contentDescription = "重命名铃声") }
            IconButton(onDelete, Modifier.size(48.dp), colors = IconButtonDefaults.iconButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(LucideIcons.Trash2, contentDescription = "删除铃声")
            }
        }
    }
}

@Composable
private fun PreviewButton(isPreviewing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().heightIn(min = 48.dp),
        shape = MaterialTheme.shapes.small,
        colors = VisionGuardControlColors.outlinedButton(contentColor = if (isPreviewing) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
    ) { Text(if (isPreviewing) "停止试听" else "试听") }
}

@Composable
private fun RenameRingtoneDialog(oldName: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var input by remember { mutableStateOf(oldName) }
    NotifierDialog(
        title = "重命名铃声",
        onDismiss = onDismiss,
        confirmButton = {
            TextButton({ onConfirm(input) }, Modifier.heightIn(min = 48.dp), enabled = input.isNotBlank(), colors = VisionGuardControlColors.textButton(), shape = MaterialTheme.shapes.small) { Text("保存") }
        },
        dismissButton = { TextButton(onDismiss, Modifier.heightIn(min = 48.dp), shape = MaterialTheme.shapes.small) { Text("取消") } }
    ) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isError = input.isBlank(),
            label = { Text("铃声名称") },
            supportingText = { if (input.isBlank()) Text("请输入名称") },
            textStyle = MaterialTheme.typography.bodyLarge,
            shape = MaterialTheme.shapes.small
        )
    }
}
