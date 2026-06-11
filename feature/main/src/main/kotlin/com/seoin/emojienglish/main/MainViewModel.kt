package com.seoin.emojienglish.main

import androidx.lifecycle.ViewModel
import com.seoin.emojienglish.data.MasterModeState
import com.seoin.emojienglish.voice.VoiceController
import com.seoin.emojienglish.voice.VoicePrompt
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val voiceController: VoiceController,
    private val masterMode: MasterModeState,
) : ViewModel() {

    // --- Voice (global 🎙 sheet) ---
    val activeVoicePrompt: StateFlow<VoicePrompt?> = voiceController.active
    fun openFreeTalkVoice() =
        voiceController.openSheet(VoicePrompt(templateId = "free_talk", contextLabel = "자유 대화"))
    fun closeVoice() = voiceController.close()

    // --- Master toggle (thin bottom bar 🔒) ---
    val masterUnlocked: StateFlow<Boolean> = masterMode.unlocked
    val keepMaster: StateFlow<Boolean> = masterMode.keepUnlocked

    /**
     * Handle a tap on the master button.
     * @return true if it was handled (toggled), false if a PIN is required first.
     */
    fun toggleMaster(): Boolean = when {
        masterMode.unlocked.value -> { masterMode.exitMaster(); true }
        masterMode.keepUnlocked.value -> { masterMode.enterMaster(keep = true); true }
        else -> false // caller shows the PIN dialog
    }

    /** Submit the PIN dialog. [keep] = "마스터 모드 유지" checkbox. */
    fun submitPin(pin: String, keep: Boolean): Boolean {
        if (!masterMode.verifyPin(pin)) return false
        masterMode.enterMaster(keep)
        return true
    }
}
