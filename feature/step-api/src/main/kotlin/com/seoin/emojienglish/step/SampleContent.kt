package com.seoin.emojienglish.step

import com.seoin.emojienglish.model.Chunk
import com.seoin.emojienglish.model.Comic
import com.seoin.emojienglish.model.ComicPanel
import com.seoin.emojienglish.model.LessonContent
import com.seoin.emojienglish.model.Passage
import com.seoin.emojienglish.model.Question
import com.seoin.emojienglish.model.Word

/**
 * Sample content for `@Preview` and [FakeStepSession] (§12.1). Mirrors the
 * "At a Restaurant" unit from §5.2 so step previews look realistic without the
 * full app/content pipeline.
 */
object SampleContent {
    val restaurant: LessonContent = LessonContent(
        words = listOf(
            Word(
                id = "w_order", text = "order", meaningKo = "주문하다", emoji = "🧾",
                example = "I want to order pasta.", similarWords = listOf("request", "ask for"),
            ),
            Word(
                id = "w_delicious", text = "delicious", meaningKo = "맛있는", emoji = "😋",
                example = "The pasta is delicious.", similarWords = listOf("tasty", "yummy"),
            ),
        ),
        comic = Comic(
            panels = listOf(
                ComicPanel(
                    id = "p1", speaker = "Mina", text = "I want to order pasta.",
                    emojiScene = "👩‍🦰 ➜ 🧾 ➜ 🍝", focusWordIds = listOf("w_order"),
                ),
                ComicPanel(
                    id = "p2", speaker = "Waiter", text = "Sure! Anything to drink?",
                    emojiScene = "🧑‍🍳 ➜ 🥤", focusWordIds = emptyList(),
                ),
                ComicPanel(
                    id = "p3", speaker = "Mina", text = "It is delicious!",
                    emojiScene = "🍝 ➜ 😋", focusWordIds = listOf("w_delicious"),
                ),
            ),
        ),
        passage = Passage(
            id = "passage_01", title = "Lunch Time",
            text = "Mina goes to a restaurant. She wants to order pasta. The pasta is delicious.",
            sentences = listOf(
                "Mina goes to a restaurant.",
                "She wants to order pasta.",
                "The pasta is delicious.",
            ),
            chunks = listOf(
                Chunk(id = "c1", text = "Mina goes to a restaurant.", meaningKo = "미나는 식당에 간다."),
                Chunk(id = "c2", text = "She wants to order pasta.", meaningKo = "그녀는 파스타를 주문하고 싶다."),
                Chunk(id = "c3", text = "The pasta is delicious.", meaningKo = "파스타는 맛있다."),
            ),
            questions = listOf(
                Question(
                    id = "q1", type = "multiple_choice",
                    question = "What does Mina want to order?",
                    choices = listOf("Pasta", "Coffee", "Cake"), answer = "Pasta",
                ),
            ),
        ),
    )
}
