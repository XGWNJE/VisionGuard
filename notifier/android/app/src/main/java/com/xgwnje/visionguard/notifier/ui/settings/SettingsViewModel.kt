// src/main/java/com/example/vg_notifier/ui/settings/SettingsViewModel.kt
package com.xgwnje.visionguard.notifier.ui.settings

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.xgwnje.visionguard.notifier.PermissionUtils
import com.xgwnje.visionguard.notifier.R
import com.xgwnje.visionguard.notifier.RingtoneLibrary
import com.xgwnje.visionguard.notifier.SharedPreferencesHelper
import com.xgwnje.visionguard.notifier.AlertRecord
import com.xgwnje.visionguard.notifier.NotificationLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val sharedPreferencesHelper = SharedPreferencesHelper(context)
    private val TAG = "SettingsViewModel"
    private val _selectedRingtoneName = mutableStateOf(context.getString(R.string.no_ringtone_selected))
    val selectedRingtoneName: State<String> = _selectedRingtoneName
    private val _defaultLoopCount = mutableStateOf(sharedPreferencesHelper.getDefaultLoopCount())
    val defaultLoopCount: State<Int> = _defaultLoopCount
    private val _alertHistoryVersion = mutableStateOf(0)
    val alertHistoryVersion: State<Int> = _alertHistoryVersion
    private val _ringtoneLibrary = mutableStateListOf<Pair<String, String>>()
    val ringtoneLibrary: List<Pair<String, String>> = _ringtoneLibrary
    private val _previewVersion = mutableStateOf(0)
    val previewVersion: State<Int> = _previewVersion
    private val _recordingVersion = mutableStateOf(0)
    val recordingVersion: State<Int> = _recordingVersion
    private val historyListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "alert_history") _alertHistoryVersion.value++
    }
    private val previewListener: () -> Unit = { _previewVersion.value++ }
    init {
        sharedPreferencesHelper.prefs.registerOnSharedPreferenceChangeListener(historyListener)
        RingtoneLibrary.onPreviewStateChanged = previewListener
        updateSelectedRingtoneName()
        loadRingtoneLibrary()
    }
    fun disposeAccount() {
        sharedPreferencesHelper.prefs.unregisterOnSharedPreferenceChangeListener(historyListener)
        RingtoneLibrary.stopPreview()
        if (RingtoneLibrary.onPreviewStateChanged === previewListener) RingtoneLibrary.onPreviewStateChanged = null
    }
    override fun onCleared() { disposeAccount(); super.onCleared() }
    val isRecording: Boolean get() = RingtoneLibrary.isRecording
    val previewingFileName: String? get() = RingtoneLibrary.previewingFileName
    fun onDefaultLoopCountSelected(count: Int) {
        val normalized = count.coerceIn(
            SharedPreferencesHelper.MIN_LOOP_COUNT,
            SharedPreferencesHelper.MAX_LOOP_COUNT
        )
        _defaultLoopCount.value = normalized
        sharedPreferencesHelper.saveDefaultLoopCount(normalized)
        Log.i(TAG, "Default loop count saved: $normalized")
    }

    fun getDefaultRingtoneValue(): String? = sharedPreferencesHelper.getRingtoneValue()

    fun loadRingtoneLibrary() {
        val entries = sharedPreferencesHelper.getRingtoneLibraryMap()
            .toList()
            .sortedBy { it.second.lowercase() }
        _ringtoneLibrary.clear()
        _ringtoneLibrary.addAll(entries)
    }

    fun importRingtone(uri: Uri, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                RingtoneLibrary.importFromUri(context, uri)
            }
            if (result != null) {
                val (fileName, displayName) = result
                sharedPreferencesHelper.putRingtoneLibraryEntry(fileName, displayName)
                loadRingtoneLibrary()
            }
            onResult(result != null)
        }
    }

    fun deleteLibraryRingtone(fileName: String): Boolean {
        val file = java.io.File(RingtoneLibrary.libraryDir(context), fileName)
        if (file.exists() && !RingtoneLibrary.deleteFile(context, fileName)) return false
        sharedPreferencesHelper.removeRingtoneLibraryEntry(fileName)
        loadRingtoneLibrary()
        updateSelectedRingtoneName()
        return true
    }

    fun renameLibraryRingtone(fileName: String, newName: String) {
        val trimmed = com.xgwnje.visionguard.account.DisplayNamePolicy.normalize(newName)
        sharedPreferencesHelper.putRingtoneLibraryEntry(fileName, trimmed)
        loadRingtoneLibrary()
        updateSelectedRingtoneName()
    }

    // --- 录音 ---


    fun startRecording(): Boolean {
        val ok = RingtoneLibrary.startRecording(context)
        _recordingVersion.value++
        return ok
    }

    fun stopRecording(): Boolean {
        val result = RingtoneLibrary.stopRecording(context)
        _recordingVersion.value++
        if (result != null) {
            val (fileName, displayName) = result
            sharedPreferencesHelper.putRingtoneLibraryEntry(fileName, displayName)
            loadRingtoneLibrary()
            return true
        }
        return false
    }

    // --- 试听 ---


    fun togglePreview(fileName: String) {
        RingtoneLibrary.togglePreview(context, fileName)
        _previewVersion.value++
    }

    fun togglePresetPreview(preset: RingtoneLibrary.PresetRingtone) {
        RingtoneLibrary.togglePresetPreview(context, preset)
        _previewVersion.value++
    }

    fun stopPreview() {
        RingtoneLibrary.stopPreview()
        _previewVersion.value++
    }

    fun getAlertHistory(): List<AlertRecord> {
        return sharedPreferencesHelper.getAlertHistory()
    }

    fun clearAlertHistory() {
        sharedPreferencesHelper.clearAlertHistory()
        _alertHistoryVersion.value++
    }

    fun onRingtoneValueSelected(value: String?) {
        // 先保存再刷新展示名，否则读到的是旧值（原逻辑会把行内名称卡在旧值）
        sharedPreferencesHelper.saveRingtoneValue(value)
        updateSelectedRingtoneName()
        Log.i(TAG, "Ringtone value selected and saved immediately: $value")
    }

    private fun ringtoneValueDisplayName(value: String): String {
        return when {
            value == RingtoneLibrary.SILENT_VALUE -> "静音"
            RingtoneLibrary.isPresetValue(value) -> RingtoneLibrary.presetDisplayName(value) ?: "预设铃声"
            RingtoneLibrary.isLibraryFile(value) -> {
                val fileName = java.io.File(value).name
                val libraryName = sharedPreferencesHelper.getRingtoneLibraryMap()[fileName]
                when {
                    libraryName != null -> libraryName
                    // 库条目已删（文件随之删除）：原值已失效，提示会回落，不展示裸路径
                    !java.io.File(value).exists() -> context.getString(R.string.ringtone_file_missing)
                    else -> fileName.substringBeforeLast('.')
                }
            }
            else -> {
                try {
                    RingtoneManager.getRingtone(context, Uri.parse(value))?.getTitle(context)
                        ?: context.getString(R.string.default_ringtone_name)
                } catch (e: Exception) {
                    Log.e(TAG, "Error getting ringtone title: $value", e)
                    "未知铃声"
                }
            }
        }
    }

    private fun updateSelectedRingtoneName() {
        val value = sharedPreferencesHelper.getRingtoneValue()
        _selectedRingtoneName.value = if (value != null) {
            ringtoneValueDisplayName(value)
        } else {
            context.getString(R.string.no_ringtone_selected)
        }
    }

}

class SettingsViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SettingsViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return SettingsViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
