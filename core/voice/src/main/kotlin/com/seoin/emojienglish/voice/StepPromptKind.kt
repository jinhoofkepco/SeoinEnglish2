package com.seoin.emojienglish.voice

/**
 * The catalog of per-step prompt *kinds* (요구사항: 스텝별 프롬프트 기능 분리).
 *
 * A Step never writes raw turn scripts; it picks a [StepPromptKind] and lets the
 * session compile it. This is the "프롬프트 규칙을 미리 정해 음성 입력을 빠르게"
 * goal (목표①): the rules are fixed up front so a sentence can be read / explained
 * / quizzed with one tap and the study flow never stalls.
 *
 * Example (지문 해석 스텝, per sentence): [READ_ALONG] → [EXPLAIN] → [QUIZ_VOCAB]
 * → [QUIZ_CONTEXT]. The list is intentionally open — new kinds are added here and
 * mapped in [toTurnScript]; nothing else changes.
 */
enum class StepPromptKind {
    /** Read the payload aloud once, slowly, for the child to repeat after. */
    READ_ALONG,

    /** Explain the payload in very easy English. Read-only — mic never opens. */
    EXPLAIN,

    /** Ask a vocabulary question about the payload, then listen + judge. */
    QUIZ_VOCAB,

    /** Ask a context/comprehension question, then listen + judge. */
    QUIZ_CONTEXT,

    /** Free conversation — no fixed instruction, mic fully manual (목표③). */
    FREE_TALK,
}

/** Whether a kind opens the child's mic after speaking (drives mic auto-gate). */
val StepPromptKind.isAskAndListen: Boolean
    get() = this == StepPromptKind.QUIZ_VOCAB || this == StepPromptKind.QUIZ_CONTEXT

/**
 * Compile a [VoicePrompt] into the engine's [VoiceTurnScript] (§B-1). Standing
 * directives (slow/easy speech, silent tag) are prepended here so Step authors
 * never type them (§C-1). [VoicePrompt.payload] becomes the read-aloud text.
 */
fun VoicePrompt.toTurnScript(): VoiceTurnScript {
    val ctx = contextLabel.ifBlank { templateId }
    return when (kind) {
        StepPromptKind.READ_ALONG -> VoiceTurnScript(
            role = TurnRole.READ_ONLY,
            instruction = directive(
                "${StandingDirectives.READ_SLOWLY} Read this aloud once for the child to repeat ($ctx).",
            ),
            payload = payload,
        )

        // Word-explain (요구사항): the word goes INTO the instruction, so the turn
        // payload is cleared to avoid sending it twice.
        StepPromptKind.EXPLAIN -> VoiceTurnScript(
            role = TurnRole.READ_ONLY,
            instruction = directive("$payload 할 수 있는 한 가장 느리게 말해줘."),
            payload = "",
        )

        StepPromptKind.QUIZ_VOCAB -> VoiceTurnScript(
            role = TurnRole.ASK_AND_LISTEN,
            instruction = directive(
                "Ask ONE short vocabulary question about this, then wait for the child ($ctx).",
            ),
            payload = payload,
            evalRubric = EvalRubric(
                onWrong = "Gently give the right word and ask them to say it once.",
                onRight = "Praise briefly and move on.",
            ),
        )

        StepPromptKind.QUIZ_CONTEXT -> VoiceTurnScript(
            role = TurnRole.ASK_AND_LISTEN,
            instruction = directive(
                "Ask ONE short question about the meaning/context, then wait for the child ($ctx).",
            ),
            payload = payload,
            evalRubric = EvalRubric(
                onWrong = "Explain the idea in one easy sentence, then ask once more.",
                onRight = "Praise briefly and move on.",
            ),
        )

        StepPromptKind.FREE_TALK -> VoiceTurnScript(
            role = TurnRole.READ_ONLY,
            instruction = directive("Chat freely and warmly with the child ($ctx)."),
            payload = payload,
        )
    }
}

private fun directive(body: String): String =
    "${StandingDirectives.SPEAK_STYLE} ${StandingDirectives.TAG_SILENCE} $body"
