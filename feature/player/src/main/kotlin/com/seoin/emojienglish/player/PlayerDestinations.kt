package com.seoin.emojienglish.player

import com.seoin.emojienglish.model.NavRoutes

/**
 * Player navigation contract. :app wires these into the NavHost; home/book/master
 * screens build a start route with the helpers below.
 */
object PlayerDestinations {
    const val ARG_MODE = "mode"
    const val ARG_BOOK = "bookId"
    const val ARG_UNIT = "unitId"
    const val ARG_PLAN = "planId"
    const val ARG_INDEX = "index"   // optional start step (master deep-link)

    const val MODE_UNIT = "unit"
    const val MODE_PLAN = "plan"

    const val ROUTE =
        "${NavRoutes.PLAYER}?$ARG_MODE={$ARG_MODE}&$ARG_BOOK={$ARG_BOOK}" +
            "&$ARG_UNIT={$ARG_UNIT}&$ARG_PLAN={$ARG_PLAN}&$ARG_INDEX={$ARG_INDEX}"

    fun unit(bookId: String, unitId: String, index: Int = 0): String =
        "${NavRoutes.PLAYER}?$ARG_MODE=$MODE_UNIT&$ARG_BOOK=$bookId&$ARG_UNIT=$unitId&$ARG_INDEX=$index"

    fun plan(planId: String): String =
        "${NavRoutes.PLAYER}?$ARG_MODE=$MODE_PLAN&$ARG_PLAN=$planId"
}
