package com.seoin.emojienglish.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.seoin.emojienglish.designsystem.Pill
import com.seoin.emojienglish.model.Book

/**
 * Unified landing (요구사항 ③): "오늘의 할일" as a one-line horizontal list on the
 * top third, and books as an icon grid below. Tapping the today title or a book
 * navigates; the bottom tabs/rail are gone.
 */
@Composable
fun HomeScreen(
    onStartUnit: (bookId: String, unitId: String) -> Unit,
    onOpenBook: (bookId: String) -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val masterOn by vm.masterUnlocked.collectAsStateWithLifecycle()
    val plan = vm.todayPlan()
    val books = vm.books()

    Column(Modifier.fillMaxSize()) {
        // --- Top third: today's plan, one line, horizontally scrollable ---
        Surface(
            modifier = Modifier.fillMaxWidth().weight(0.34f),
            tonalElevation = 1.dp,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("오늘의 할일", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (plan == null || plan.items.isEmpty()) {
                        item {
                            vm.firstUnitRef()?.let { (bookId, unitId) ->
                                AssistChip(
                                    onClick = { onStartUnit(bookId, unitId) },
                                    label = { Text("데모: 첫 단원 시작 ▶") },
                                )
                            } ?: Text("등록된 계획이 없습니다.", style = MaterialTheme.typography.bodySmall)
                        }
                    } else {
                        items(plan.items) { item ->
                            AssistChip(
                                onClick = { onStartUnit(item.bookId, item.unitId) },
                                label = {
                                    Text("${item.unitId} · ${item.stepId ?: item.stepSelector} ×${item.repeat}")
                                },
                            )
                        }
                    }
                }
            }
        }

        // --- Remaining: books as icons ---
        Column(Modifier.fillMaxWidth().weight(0.66f).padding(16.dp)) {
            Text("책장", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize().padding(top = 12.dp),
            ) {
                gridItems(books) { book -> BookIcon(book, masterOn) { onOpenBook(book.bookId) } }
            }
        }
    }
}

@Composable
private fun BookIcon(book: Book, masterOn: Boolean, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(book.coverEmoji, style = MaterialTheme.typography.displaySmall)
            Text(
                book.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            Text("${book.units.size}단원 · ${book.level}", style = MaterialTheme.typography.labelSmall)
            if (masterOn) Pill("이력")
        }
    }
}

/**
 * Book detail — unit list (§3). On an 11" tablet this becomes a list-detail
 * 2-pane in M6; for the skeleton it is a single adaptive list.
 */
@Composable
fun BookDetailScreen(
    bookId: String,
    onStartUnit: (bookId: String, unitId: String) -> Unit,
    vm: HomeViewModel = hiltViewModel(),
) {
    val book = vm.book(bookId)
    Column(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (book == null) {
            Text("책을 찾을 수 없습니다: $bookId")
            return@Column
        }
        Text("${book.coverEmoji}  ${book.title}", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        book.units.forEach { unit ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(unit.title, style = MaterialTheme.typography.titleMedium)
                    Text("${unit.steps.size}개 스텝", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { onStartUnit(book.bookId, unit.unitId) }) {
                        Text("단원 학습 시작")
                    }
                }
            }
        }
    }
}
