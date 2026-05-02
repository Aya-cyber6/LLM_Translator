package com.translator

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.translator.screen.AudioTranslationScreen
import com.translator.screen.TextTranslationScreen
import com.translator.ui.viewmodel.AudioTranslationViewModel
import com.translator.ui.viewmodel.TranslationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val textViewModel: TranslationViewModel by viewModels()
    private val audioViewModel: AudioTranslationViewModel by viewModels()
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* UI disables mic button automatically if denied */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        initModel()

        setContent {
                TranslatorApp(
                    textViewModel = textViewModel,
                    audioViewModel = audioViewModel
                )
        }
    }

    // -------------------------------------------------------------------------
    // Copy model asset once, init engine, share with audio VM
    // -------------------------------------------------------------------------
    private fun initModel() {
        lifecycleScope.launch(Dispatchers.IO) {
            val modelFile = File(filesDir, "Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm")
            if (!modelFile.exists()) {
                assets.open("Gemma3-1B-IT_multi-prefill-seq_q4_ekv4096.litertlm").use { input ->
                    modelFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            textViewModel.initializeEngine(modelFile.absolutePath)

            textViewModel.waitForEngine { engine ->
                audioViewModel.attachEngine(engine)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Root composable with bottom-nav tabs
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TranslatorApp(
    textViewModel: TranslationViewModel,
    audioViewModel: AudioTranslationViewModel
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LLM Translator") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Translate, contentDescription = null) },
                    label = { Text("Text") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.Mic, contentDescription = null) },
                    label = { Text("Audio") }
                )
            }
        }
    ) { innerPadding ->
        when (selectedTab) {
            0 -> TextTranslationScreen(
                viewModel = textViewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
            1 -> AudioTranslationScreen(
                viewModel = audioViewModel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            )
        }
    }
}