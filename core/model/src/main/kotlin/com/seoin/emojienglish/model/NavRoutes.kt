package com.seoin.emojienglish.model

/**
 * Route constants shared across modules (kept in core:model so neither :app nor
 * any feature has to depend on another to know them — §2).
 *
 * Chrome is no longer route-derived: the thin bottom bar is global (owned by the
 * AppShell) and each screen owns its own top region (home layout vs the study
 * step-navigator bar). So there is no Chrome enum anymore.
 */
object NavRoutes {
    /** Unified landing: today's plan (top) + book icons (below). */
    const val HOME = "home"
    const val BOOK = "book"            // book/{bookId}
    const val PLAYER = "player"        // player?...  (study + master step view)
    const val MASTER = "master"        // central master dashboard (log)

    fun book(bookId: String) = "$BOOK/$bookId"
}
