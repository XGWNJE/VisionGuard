package com.xgwnje.visionguard.notifier.ui.settings

import android.app.Activity
import android.content.Intent
import android.media.RingtoneManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xgwnje.visionguard.account.DisplayNamePolicy
import com.xgwnje.visionguard.icons.LucideIcons
import com.xgwnje.visionguard.notifier.*
import com.xgwnje.visionguard.notifier.ui.*
import com.xgwnje.visionguard.notifier.ui.dialogs.PermissionGuideDialog
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun RingtoneLibraryScreen(viewModel: SettingsViewModel) {
    val context = LocalContext.current
    val previewVersion by viewModel.previewVersion
    val recordingVersion by viewModel.recordingVersion
    val loops by viewModel.defaultLoopCount
    val selectedName by viewModel.selectedRingtoneName
    val entries = viewModel.ringtoneLibrary
    val previewing = remember(previewVersion) { viewModel.previewingFileName }
    val recording = remember(recordingVersion) { viewModel.isRecording }
    var message by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    var seconds by remember { mutableLongStateOf(0) }
    var micGuide by remember { mutableStateOf(false) }
    var renameFile by rememberSaveable { mutableStateOf<String?>(null) }
    var renameValue by rememberSaveable { mutableStateOf("") }
    var renameError by remember { mutableStateOf<String?>(null) }
    var deleteTarget by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleteError by remember { mutableStateOf<String?>(null) }
    val feedback: (String, Boolean) -> Unit = { value, error -> message = value; failure = error }
    LaunchedEffect(recording) { seconds = 0; while (recording) { delay(1000); seconds++ } }
    DisposableEffect(Unit) { onDispose { viewModel.stopPreview(); viewModel.cancelRecording() } }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            importing = true
            viewModel.importRingtone(uri) { ok -> importing = false; feedback(if (ok) "已导入音频" else "导入失败，请选择可读取的音频", !ok) }
        }
    }
    val systemPicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            @Suppress("DEPRECATION")
            val uri = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
            runCatching { viewModel.onRingtoneValueSelected(uri?.toString() ?: RingtoneLibrary.SILENT_VALUE) }
                .onFailure { feedback("铃声保存失败，请重试", true) }
        }
    }
    val microphone = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) micGuide = true
        else if (!viewModel.startRecording()) feedback("录音启动失败，请重试", true)
    }
    val sounds: List<Pair<String, String?>> = listOf("静音" to RingtoneLibrary.SILENT_VALUE, "系统默认闹钟铃声" to null) +
        RingtoneLibrary.PRESETS.map { it.displayName to RingtoneLibrary.presetValue(context, it) } +
        entries.map { (file, label) -> label to File(RingtoneLibrary.libraryDir(context), file).absolutePath }
    val preview: (String?, RingtoneLibrary.PresetRingtone?) -> Unit = { file, preset ->
        val key = file ?: "preset:${preset!!.rawName}"
        val wasPlaying = viewModel.previewingFileName == key
        if (SharedPreferencesHelper(context).getActiveAlert() != null || viewModel.isRecording) feedback("报警或录音期间不能试听", true)
        else {
            if (preset != null) viewModel.togglePresetPreview(preset) else viewModel.togglePreview(file!!)
            if (!wasPlaying && viewModel.previewingFileName != key) feedback("试听失败，请检查音频", true)
        }
    }
    NotifierLongPage(listOf(
        NotifierSectionSpec("播放策略") {
            NotifierSection("播放策略") {
                NotifierSelect("默认铃声", selectedName, sounds.map { it.first } + "选择系统铃声…") { index ->
                    runCatching {
                        if (index == sounds.size) systemPicker.launch(Intent(RingtoneManager.ACTION_RINGTONE_PICKER).apply {
                            putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_ALARM)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                            putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, viewModel.getDefaultRingtoneValue()?.takeIf { it.startsWith("content://") }?.let(Uri::parse))
                        }) else viewModel.onRingtoneValueSelected(sounds[index].second)
                    }.onFailure { feedback("铃声选择或保存失败，请重试", true) }
                }
                NotifierSelect("播放次数", "$loops 次", (1..10).map { "$it 次" }) { index ->
                    runCatching { viewModel.onDefaultLoopCountSelected(index + 1) }.onFailure { feedback("次数保存失败，请重试", true) }
                }
                NotifierMessage(message, failure)
            }
        },
        NotifierSectionSpec("内置铃声") {
            NotifierSection("内置铃声") {
                RingtoneLibrary.PRESETS.forEach { preset ->
                    AudioRow(preset.displayName, previewing == "preset:${preset.rawName}", { preview(null, preset) })
                }
            }
        },
        NotifierSectionSpec("我的音频") {
            NotifierSection("我的音频") {
                Text("${entries.size} / 100", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                if (entries.isEmpty()) Text("暂无自定义音频", color = MaterialTheme.colorScheme.onSurfaceVariant)
                entries.forEach { (file, label) ->
                    AudioRow(label, previewing == file, { preview(file, null) }, actions = {
                        IconButton({ renameFile = file; renameValue = label; renameError = null }, enabled = !recording) { Icon(LucideIcons.Pencil, "重命名 $label", Modifier.size(18.dp)) }
                        IconButton({ deleteTarget = file to label; deleteError = null }, enabled = !recording) { Icon(LucideIcons.Trash2, "删除 $label", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error) }
                    })
                    if (renameFile == file) {
                        OutlinedTextField(renameValue, { if (DisplayNamePolicy.acceptsDraft(it)) { renameValue = it; renameError = null } else renameError = DisplayNamePolicy.HINT },
                            Modifier.fillMaxWidth(), label = { Text("音频名称") }, singleLine = true,
                            isError = renameError != null || DisplayNamePolicy.error(renameValue) != null, shape = MaterialTheme.shapes.small)
                        Row {
                            TextButton({
                                runCatching { viewModel.renameLibraryRingtone(file, renameValue) }.onSuccess { renameFile = null }.onFailure { renameError = "改名失败，请重试" }
                            }, enabled = renameError == null && DisplayNamePolicy.error(renameValue) == null) { Text("保存") }
                            TextButton({ renameFile = null }) { Text("取消") }
                        }
                        NotifierMessage(renameError, true)
                    }
                }
            }
        },
        NotifierSectionSpec("导入与录音") {
            NotifierSection("导入与录音") {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton({
                        runCatching { importPicker.launch(arrayOf("audio/*")) }.onFailure { feedback("文件选择不可用", true) }
                    }, enabled = !importing && !recording && entries.size < 100) { Text(if (importing) "导入中…" else "导入音频") }
                    Button({
                        if (recording) { val ok = viewModel.stopRecording(); feedback(if (ok) "录音已保存" else "录音太短或保存失败", !ok) }
                        else if (PermissionUtils.isRecordAudioGranted(context)) { if (!viewModel.startRecording()) feedback("录音启动失败，请重试", true) }
                        else microphone.launch(android.Manifest.permission.RECORD_AUDIO)
                    }, enabled = !importing && (recording || entries.size < 100)) { Text(if (recording) "停止并保存" else "开始录音") }
                }
                if (recording) Text("正在录音 · $seconds 秒", color = MaterialTheme.colorScheme.error)
                if (importing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                NotifierMessage(message, failure)
            }
        }
    ))
    if (micGuide) PermissionGuideDialog("需要麦克风权限", "请在系统设置中允许使用麦克风。",
        onConfirm = { micGuide = false; (context as? Activity)?.let(PermissionUtils::openAppDetailsSettings) }, onDismiss = { micGuide = false })
    deleteTarget?.let { (file, label) ->
        NotifierDialog("删除音频", { deleteTarget = null }, focusOnDismiss = true,
            confirmButton = { TextButton({
                runCatching { viewModel.deleteLibraryRingtone(file) }.onSuccess { ok -> if (ok) deleteTarget = null else deleteError = "音频正在使用或删除失败，已保留" }.onFailure { deleteError = "删除失败，已保留" }
            }) { Text("删除", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton({ deleteTarget = null }) { Text("取消") } }) {
            Text("删除「$label」？")
            NotifierMessage(deleteError, true)
        }
    }
}

@Composable
private fun AudioRow(label: String, playing: Boolean, onPreview: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        TextButton(onPreview) { Text(if (playing) "停止试听" else "试听") }
        actions()
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
