package com.seoin.emojienglish.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.seoin.emojienglish.home.BookDetailScreen
import com.seoin.emojienglish.home.HomeScreen
import com.seoin.emojienglish.master.MasterScreen
import com.seoin.emojienglish.model.NavRoutes
import com.seoin.emojienglish.player.PlayerDestinations
import com.seoin.emojienglish.player.PlayerScreen

/**
 * The app's screen graph (§2, §3). The only place that references
 * home/player/master together; feature:main wraps this in the global chrome.
 * Step modules are never referenced here — the Player resolves them via the
 * Hilt StepRegistry map.
 */
@Composable
fun AppNavHost(navController: NavHostController, modifier: Modifier = Modifier) {
    NavHost(
        navController = navController,
        startDestination = NavRoutes.HOME,
        modifier = modifier,
    ) {
        composable(NavRoutes.HOME) {
            HomeScreen(
                onStartUnit = { bookId, unitId ->
                    navController.navigate(PlayerDestinations.unit(bookId, unitId))
                },
                onOpenBook = { bookId -> navController.navigate(NavRoutes.book(bookId)) },
            )
        }

        composable(
            route = "${NavRoutes.BOOK}/{bookId}",
            arguments = listOf(navArgument("bookId") { type = NavType.StringType }),
        ) { entry ->
            val bookId = entry.arguments?.getString("bookId").orEmpty()
            BookDetailScreen(
                bookId = bookId,
                onStartUnit = { b, u -> navController.navigate(PlayerDestinations.unit(b, u)) },
            )
        }

        composable(
            route = PlayerDestinations.ROUTE,
            arguments = listOf(
                navArgument(PlayerDestinations.ARG_MODE) { nullable = true; defaultValue = null },
                navArgument(PlayerDestinations.ARG_BOOK) { nullable = true; defaultValue = null },
                navArgument(PlayerDestinations.ARG_UNIT) { nullable = true; defaultValue = null },
                navArgument(PlayerDestinations.ARG_PLAN) { nullable = true; defaultValue = null },
                navArgument(PlayerDestinations.ARG_INDEX) { nullable = true; defaultValue = null },
            ),
        ) {
            PlayerScreen(
                onExit = { navController.popBackStack() },
                onHome = {
                    navController.navigate(NavRoutes.HOME) {
                        popUpTo(NavRoutes.HOME) { inclusive = false }
                        launchSingleTop = true
                    }
                },
                onOpenMaster = { navController.navigate(NavRoutes.MASTER) },
            )
        }

        composable(NavRoutes.MASTER) {
            MasterScreen(
                onOpenStep = { bookId, unitId, index ->
                    navController.navigate(PlayerDestinations.unit(bookId, unitId, index))
                },
                onHome = {
                    navController.navigate(NavRoutes.HOME) {
                        popUpTo(NavRoutes.HOME) { inclusive = false }
                        launchSingleTop = true
                    }
                },
            )
        }
    }
}
