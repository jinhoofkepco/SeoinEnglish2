package com.seoin.emojienglish.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.rememberNavController
import com.seoin.emojienglish.designsystem.EmojiEnglishTheme
import com.seoin.emojienglish.main.AppShell
import com.seoin.emojienglish.main.MainViewModel
import com.seoin.emojienglish.model.NavRoutes
import dagger.hilt.android.AndroidEntryPoint

/**
 * The single Activity. It only sets up the theme + AppShell + NavHost — all
 * screen logic lives in feature modules (§2).
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requestMic =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* WebView grant retries on next request */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The GPT-Voice WebView needs RECORD_AUDIO before it can capture the mic.
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestMic.launch(Manifest.permission.RECORD_AUDIO)
        }
        setContent { EmojiEnglishRoot() }
    }
}

@Composable
private fun EmojiEnglishRoot() {
    EmojiEnglishTheme {
        val navController = rememberNavController()
        val mainViewModel: MainViewModel = hiltViewModel()
        AppShell(
            vm = mainViewModel,
            onOpenMasterLog = { navController.navigate(NavRoutes.MASTER) },
            onOpenAuthoring = { navController.navigate(NavRoutes.AUTHORING) },
        ) { modifier ->
            AppNavHost(navController = navController, modifier = modifier)
        }
    }
}
