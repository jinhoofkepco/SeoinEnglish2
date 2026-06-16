package com.seoin.emojienglish.designsystem

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import java.io.File

/**
 * Decode a content image referenced by a unit-relative path
 * (e.g. `"books/book_a/images/u03/grid.png"`), trying **filesDir first, then
 * bundled assets**. Mirrors [AssetLessonRepository]'s merge so runtime content
 * written to `filesDir/<path>` shows up exactly like bundled assets.
 */
fun decodeContentBitmap(context: Context, path: String): Bitmap? {
    if (path.isBlank()) return null
    val file = File(context.filesDir, path)
    val stream = when {
        file.isFile -> file.inputStream()
        else -> runCatching { context.assets.open(path) }.getOrNull()
    } ?: return null
    return stream.use { runCatching { BitmapFactory.decodeStream(it) }.getOrNull() }
}

/** Crop one cell out of a [cols]×[rows] grid bitmap (row-major, 0-based [cell]). */
private fun cropCell(bmp: Bitmap, cols: Int, rows: Int, cell: Int): Bitmap {
    val cw = bmp.width / cols
    val ch = bmp.height / rows
    if (cw <= 0 || ch <= 0) return bmp
    val r = cell / cols
    val c = cell % cols
    if (r >= rows || c >= cols) return bmp
    val x = (c * cw).coerceIn(0, bmp.width - 1)
    val y = (r * ch).coerceIn(0, bmp.height - 1)
    val w = cw.coerceAtMost(bmp.width - x)
    val h = ch.coerceAtMost(bmp.height - y)
    return runCatching { Bitmap.createBitmap(bmp, x, y, w, h) }.getOrDefault(bmp)
}

/**
 * Compose helper: remembers the decoded [ImageBitmap] for [path] (or null).
 * If [cols]/[rows] > 0 and [cell] >= 0, returns only that grid cell — lets one
 * generated grid image serve every word (한 파일, 칸 단위 표시).
 */
@Composable
fun rememberContentImage(
    path: String,
    cols: Int = 0,
    rows: Int = 0,
    cell: Int = -1,
): ImageBitmap? {
    val context = LocalContext.current
    return remember(context, path, cols, rows, cell) {
        val full = decodeContentBitmap(context, path) ?: return@remember null
        if (cols > 0 && rows > 0 && cell >= 0) cropCell(full, cols, rows, cell).asImageBitmap()
        else full.asImageBitmap()
    }
}
