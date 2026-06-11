package com.seoin.emojienglish.designsystem

/**
 * Display labels for step types, used by the navigator bar chips. A display
 * concern, so it lives in the design system (not in any step module — steps
 * stay independent). Unknown types fall back to the raw type string.
 */
fun stepTypeLabel(type: String): String = when (type) {
    "word_comic" -> "단어만화"
    "voice_explain" -> "음성설명"
    "similar_word_card" -> "비슷한말"
    "shadowing" -> "쉐도잉"
    "simple_question" -> "질문"
    "chunk_interpret" -> "청크해석"
    else -> type
}

fun stepTypeEmoji(type: String): String = when (type) {
    "word_comic" -> "🗯️"
    "voice_explain" -> "🎙️"
    "similar_word_card" -> "🃏"
    "shadowing" -> "🗣️"
    "simple_question" -> "❓"
    "chunk_interpret" -> "🧩"
    else -> "📄"
}
