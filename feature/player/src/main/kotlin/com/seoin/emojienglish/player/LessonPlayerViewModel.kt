package com.seoin.emojienglish.player

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.seoin.emojienglish.content.LessonRepository
import com.seoin.emojienglish.data.MasterModeState
import com.seoin.emojienglish.data.ProgressRepository
import com.seoin.emojienglish.data.TraceRepository
import com.seoin.emojienglish.designsystem.ChipState
import com.seoin.emojienglish.designsystem.NavStepChip
import com.seoin.emojienglish.designsystem.stepTypeEmoji
import com.seoin.emojienglish.designsystem.stepTypeLabel
import com.seoin.emojienglish.model.PlayQueue
import com.seoin.emojienglish.model.QueueItem
import com.seoin.emojienglish.model.StepDef
import com.seoin.emojienglish.model.StepResult
import com.seoin.emojienglish.model.StepTraceSnapshot
import com.seoin.emojienglish.model.TraceEvent
import com.seoin.emojienglish.step.StepFeature
import com.seoin.emojienglish.step.StepRegistry
import com.seoin.emojienglish.step.StepSession
import com.seoin.emojienglish.step.StepSpec
import com.seoin.emojienglish.step.StepSpecParseException
import com.seoin.emojienglish.voice.StepPromptKind
import com.seoin.emojienglish.voice.VoiceGateway
import com.seoin.emojienglish.voice.VoicePrompt
import com.seoin.emojienglish.voice.VoiceSession
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject

/**
 * Player ViewModel (§7.1, plus 요구사항 ①④⑦).
 *
 * Consumes one [PlayQueue]. Top navigator chips are built from the queue; the
 * current step is highlighted and (in student mode) only already-reached steps
 * are tappable. With master mode on it renders each step's [StepFeature.MasterView]
 * from the trace snapshot and lets the navigator reach every step.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LessonPlayerViewModel @Inject constructor(
    private val registry: StepRegistry,
    private val lessonRepo: LessonRepository,
    private val traceRepo: TraceRepository,
    private val progressRepo: ProgressRepository,
    private val masterMode: MasterModeState,
    private val voiceSession: VoiceSession,
    private val voiceGateway: VoiceGateway,
    private val savedState: SavedStateHandle,
) : ViewModel() {

    data class UiState(
        val sourceLabel: String = "",
        val queueSize: Int = 0,
        val index: Int = 0,
        val rawType: String = "",
        val stepFeature: StepFeature? = null,  // null → "unsupported type" card
        val spec: StepSpec? = null,
        val parseError: String? = null,
        val isCurrentComplete: Boolean = false,
        val isLast: Boolean = false,
        val occurrenceLabel: String = "",
        val currentItem: QueueItem? = null,
        val finished: Boolean = false,
        val masterMode: Boolean = false,
        val voiceActive: Boolean = false,
        val chips: List<NavStepChip> = emptyList(),
        val maxReached: Int = 0,
        val masterSnapshot: StepTraceSnapshot? = null,
    )

    private val queue: PlayQueue = buildQueue()
    /** Step type per queue position (for navigator chip labels). */
    private val chipTypes: List<String> = queue.items.map { typeOf(it) }

    /**
     * One voice session per set (요구사항: 세트 동안 한 보이스). Started once here and
     * kept alive across every step in this run; torn down in [onCleared]. The
     * persona is a placeholder until `templates.json`(서인영어_brain2) lands (§14).
     */
    private val coachingPersona: String =
        "You are 서인영어, a warm English tutor for a young Korean child. " +
            "Speak slowly in very easy English. Keep turns short. " +
            "Do not say bracket tags out loud."
    private val setupJob = viewModelScope.launch {
        if (!sampleAudioMuted()) voiceSession.startSet(coachingPersona, queue.sourceLabel)
    }

    private val initialIndex: Int =
        savedState[KEY_INDEX]
            ?: savedState.get<String>(PlayerDestinations.ARG_INDEX)?.toIntOrNull()
            ?: 0
    private val indexFlow = MutableStateFlow(initialIndex)
    private val maxReachedFlow = MutableStateFlow(initialIndex)
    private val _finished = MutableStateFlow(false)
    val finished: StateFlow<Boolean> = _finished.asStateFlow()
    private val voiceActive: StateFlow<Boolean> = voiceSession.state
        .map { it.busy || it.speaking }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val uiState: StateFlow<UiState> =
        combine(indexFlow, masterMode.unlocked, maxReachedFlow, voiceActive) { idx, master, maxR, voice ->
            PlayerInputs(idx, master, maxR, voice)
        }.flatMapLatest { input ->
            val idx = input.index
            val item = queue.itemAt(idx)
            val resultFlow = if (item != null) progressRepo.result(queue.sessionId, idx) else flowOf(null)
            resultFlow.map { result -> buildState(idx, item, result, input.master, input.maxReached, input.voiceActive) }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = buildState(indexFlow.value, queue.itemAt(indexFlow.value), null, false, maxReachedFlow.value, false),
        )

    /** The session a Step is handed (§7.1). Stateless — delegates to repos/flows. */
    fun sessionFor(item: QueueItem, queueIndex: Int): StepSession = object : StepSession {
        override val content = lessonRepo.unit(item.bookId, item.unitId).content
        override val savedResult: StateFlow<StepResult?> =
            progressRepo.result(queue.sessionId, queueIndex)
        override val voiceActive: StateFlow<Boolean> =
            this@LessonPlayerViewModel.voiceActive

        override fun trace(action: String, detail: Map<String, String>) =
            traceRepo.add(item.toTraceEvent(queue.sessionId, action, detail))

        override fun complete(result: StepResult) {
            progressRepo.save(queue.sessionId, queueIndex, item, result)
        }

        override fun requestVoice(prompt: VoicePrompt) {
            if (sampleAudioMuted()) {
                trace("audio_muted", mapOf("kind" to "requestVoice", "templateId" to prompt.templateId))
                return
            }
            // Route the step's prompt through the persistent session; wait for the
            // set to finish connecting/priming so the first prompt isn't dropped.
            viewModelScope.launch {
                setupJob.join()
                voiceSession.runPrompt(prompt)
            }
        }

        override fun speak(text: String, lang: String) {
            if (sampleAudioMuted()) {
                trace("audio_muted", mapOf("kind" to "speak", "chars" to text.length.toString()))
                return
            }
            // 다음 액션(다음 컷/문장 등 낭독)으로 넘어가면 학생 마이크를 자동으로 끈다
            // — 모든 스텝 공통. 다시 말하려면 "대화"로 열어야 한다 (피드백).
            voiceGateway.setMicOpen(false)
            val voice = voiceSession.state.value
            if (voice.busy || voice.speaking) {
                android.util.Log.d(
                    "SeoinVoice",
                    "local TTS skipped during teacher voice busy=${voice.busy} speaking=${voice.speaking}: ${text.take(48)}",
                )
                trace("tts_skipped_voice_active", mapOf("text" to text.take(80)))
                return
            }
            voiceGateway.speak(text, lang)
        }
    }

    /** Floating "다음 단계" (요구사항 ④) — only meaningful in student mode. */
    fun goNext() {
        if (voiceActive.value) {
            android.util.Log.d("SeoinVoice", "goNext blocked while teacher voice is active")
            return
        }
        val state = uiState.value
        if (!state.isCurrentComplete) return
        if (state.isLast) {
            traceSessionEnd()
            _finished.value = true
        } else {
            setIndex(indexFlow.value + 1)
        }
    }

    /** Navigator chip tap (요구사항 ①). Student: only reached steps. Master: any. */
    fun goTo(index: Int) {
        if (index !in queue.items.indices) return
        if (!masterMode.unlocked.value && voiceActive.value) {
            android.util.Log.d("SeoinVoice", "goTo blocked while teacher voice is active")
            return
        }
        val allowed = masterMode.unlocked.value || index <= maxReachedFlow.value
        if (allowed) setIndex(index)
    }

    fun chipEnabled(index: Int): Boolean =
        masterMode.unlocked.value || index <= maxReachedFlow.value

    fun openContextVoice() {
        if (sampleAudioMuted()) return
        val item = uiState.value.currentItem ?: return
        viewModelScope.launch {
            setupJob.join()
            voiceSession.runPrompt(
                VoicePrompt(
                    templateId = "free_talk",
                    variables = mapOf("stepId" to item.stepId),
                    contextLabel = "${queue.sourceLabel} · ${item.stepId}",
                    kind = StepPromptKind.FREE_TALK,
                ),
            )
        }
    }

    override fun onCleared() {
        voiceSession.endSet()
    }

    private fun setIndex(i: Int) {
        indexFlow.value = i
        if (i > maxReachedFlow.value) maxReachedFlow.value = i
        savedState[KEY_INDEX] = i
    }

    private fun buildState(
        index: Int,
        item: QueueItem?,
        result: StepResult?,
        master: Boolean,
        maxReached: Int,
        voiceActive: Boolean,
    ): UiState {
        val chips = chipTypes.mapIndexed { i, type ->
            NavStepChip(
                label = stepTypeLabel(type),
                emoji = stepTypeEmoji(type),
                state = when {
                    i == index -> ChipState.CURRENT
                    i < index -> ChipState.DONE
                    else -> ChipState.UPCOMING
                },
            )
        }
        if (item == null) {
            return UiState(
                sourceLabel = queue.sourceLabel,
                queueSize = queue.size,
                index = index,
                isLast = true,
                finished = _finished.value,
                masterMode = master,
                voiceActive = voiceActive,
                chips = chips,
                maxReached = maxReached,
            )
        }
        val unit = lessonRepo.unitOrNull(item.bookId, item.unitId)
        val stepDef = unit?.steps?.firstOrNull { it.id == item.stepId }
        val rawType = stepDef?.type ?: "unknown"
        val feature = stepDef?.let { registry.resolve(it.type) }

        var spec: StepSpec? = null
        var parseError: String? = null
        if (feature != null && stepDef != null && unit != null) {
            try {
                val stepJson = json.encodeToJsonElement(StepDef.serializer(), stepDef).jsonObject
                spec = feature.parseSpec(stepJson, unit.content)
            } catch (e: StepSpecParseException) {
                parseError = e.message
            }
        }

        val snapshot = if (master) {
            StepTraceSnapshot(
                events = traceRepo.forStep(item.bookId, item.unitId, item.stepId),
                results = progressRepo.resultsForStep(item.bookId, item.unitId, item.stepId),
            )
        } else null

        return UiState(
            sourceLabel = queue.sourceLabel,
            queueSize = queue.size,
            index = index,
            rawType = rawType,
            stepFeature = if (parseError == null) feature else null,
            spec = spec,
            parseError = parseError,
            isCurrentComplete = result?.completed == true,
            isLast = index >= queue.size - 1,
            occurrenceLabel = occurrenceLabel(item),
            currentItem = item,
            finished = _finished.value,
            masterMode = master,
            voiceActive = voiceActive,
            chips = chips,
            maxReached = maxReached,
            masterSnapshot = snapshot,
        )
    }

    private data class PlayerInputs(
        val index: Int,
        val master: Boolean,
        val maxReached: Int,
        val voiceActive: Boolean,
    )

    private fun occurrenceLabel(item: QueueItem): String =
        if (item.totalOccurrences > 1) "반복 ${item.occurrence}/${item.totalOccurrences}회차" else ""

    private fun typeOf(item: QueueItem): String =
        lessonRepo.unitOrNull(item.bookId, item.unitId)
            ?.steps?.firstOrNull { it.id == item.stepId }?.type ?: "unknown"

    private fun buildQueue(): PlayQueue {
        lessonRepo.refresh()
        val mode = savedState.get<String>(PlayerDestinations.ARG_MODE)
        return when (mode) {
            PlayerDestinations.MODE_PLAN -> {
                // PlanRepository lookup wired in M7; empty queue until then.
                PlayQueue(java.util.UUID.randomUUID().toString(), "오늘의 할일", emptyList())
            }
            else -> {
                val bookId = savedState.get<String>(PlayerDestinations.ARG_BOOK)
                val unitId = savedState.get<String>(PlayerDestinations.ARG_UNIT)
                val book = bookId?.let { lessonRepo.book(it) }
                val unit = if (bookId != null && unitId != null) lessonRepo.unitOrNull(bookId, unitId) else null
                if (book != null && unit != null) QueueCompiler.fromUnit(book, unit)
                else PlayQueue(java.util.UUID.randomUUID().toString(), "학습", emptyList())
            }
        }
    }

    private fun traceSessionEnd() {
        val item = queue.items.lastOrNull() ?: return
        traceRepo.add(item.toTraceEvent(queue.sessionId, "session_end"))
    }

    companion object {
        private fun sampleAudioMuted(): Boolean = true
        private const val KEY_INDEX = "player_queue_index"
        private val json = Json

        private fun QueueItem.toTraceEvent(
            sessionId: String,
            action: String,
            detail: Map<String, String> = emptyMap(),
        ): TraceEvent = TraceEvent(
            studentId = "student_local",
            bookId = bookId,
            unitId = unitId,
            stepId = stepId,
            occurrence = occurrence,
            sessionId = sessionId,
            action = action,
            detailJson = json.encodeToString(
                kotlinx.serialization.json.JsonObject(detail.mapValues { JsonPrimitive(it.value) }),
            ),
            at = System.currentTimeMillis(),
        )
    }
}
