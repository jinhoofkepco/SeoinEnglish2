package com.seoin.emojienglish.designsystem

/**
 * Display labels for step types, used by navigator chips.
 * Step modules stay independent; unknown types fall back to the raw type.
 */
fun stepTypeLabel(type: String): String = when (type) {
    "story_comic" -> "전체만화"
    "word_comic" -> "단어만화"
    "word_card" -> "단어카드"
    "voice_explain" -> "음성설명"
    "similar_word_card" -> "비슷한말"
    "shadowing" -> "따라읽기"
    "simple_question" -> "질문"
    "chunk_interpret" -> "청크해석"
    "passage_read" -> "지문읽기"
    else -> type
}

fun stepTypeEmoji(type: String): String = when (type) {
    "story_comic" -> "SC"
    "word_comic" -> "WC"
    "word_card" -> "카"
    "voice_explain" -> "V"
    "similar_word_card" -> "S"
    "shadowing" -> "R"
    "simple_question" -> "Q"
    "chunk_interpret" -> "C"
    "passage_read" -> "P"
    else -> "?"
}
