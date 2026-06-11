package com.seoin.emojienglish.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import com.seoin.emojienglish.designsystem.EmojiEnglishTheme
import com.seoin.emojienglish.main.AppShell
import com.seoin.emojienglish.main.MainViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single Activity. It only sets up the theme + AppShell + NavHost — all
 * screen logic lives in feature modules (§2).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { EmojiEnglishRoot() }
    }
}

@Composable
private fun EmojiEnglishRoot() {
    EmojiEnglishTheme {
        val navController = rememberNavController()
        val mainViewModel: MainViewModel = hiltViewModel()
        AppShell(vm = mainViewModel) { modifier ->
            AppNavHost(navController = navController, modifier = modifier)
        }
    }
}
