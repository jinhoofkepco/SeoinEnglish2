package com.seoin.emojienglish.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seoin.emojienglish.data.MasterModeState
import com.seoin.emojienglish.voice.PictureController
import com.seoin.emojienglish.voice.PictureState
import com.seoin.emojienglish.voice.PictureWord
import com.seoin.emojienglish.voice.VoicePanelMode
import com.seoin.emojienglish.voice.VoiceSession
import com.seoin.emojienglish.voice.VoiceSessionState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val voiceSession: VoiceSession,
    private val pictures: PictureController,
    private val masterMode: MasterModeState,
) : ViewModel() {

    // --- Voice (persistent panel, 요구사항 ⑤⑥) ---
    val voiceState: StateFlow<VoiceSessionState> = voiceSession.state

    /** 🎙 free-talk (목표③): open a session with manual mic, no auto-gate. */
    fun openFreeTalkVoice() = viewModelScope.launch { voiceSession.startFreeTalk() }

    /** Our mic on/off button — disables auto-gate (manual override). */
    fun setMicManual(open: Boolean) = voiceSession.setMicManual(open)

    fun cyclePanel() = voiceSession.cyclePanel()
    fun setPanelMode(mode: VoicePanelMode) = voiceSession.setPanelMode(mode)
    fun renameConversation(name: String) = voiceSession.rename(name)

    /** The retained ChatGPT WebView to host in the panel (null until created). */
    fun provideVoiceView() = voiceSession.provideView()

    // --- 그림창 (2번째 WebView, task 2) ---
    val pictureState: StateFlow<PictureState> = pictures.state
    fun togglePicture() = pictures.toggle()
    fun closePicture() = pictures.close()
    fun requestPicture(word: PictureWord) = pictures.requestPicture(word)
    fun providePictureView() = pictures.provideView()

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
