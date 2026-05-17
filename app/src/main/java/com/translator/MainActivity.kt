package com.translator

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.lifecycleScope
import com.translator.screen.TranslatorApp
import com.translator.ui.viewmodel.InterpreterViewModel
import com.translator.ui.viewmodel.TranslationViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {
    private val textViewModel: TranslationViewModel by viewModels()
    private val interpeterViewModel: InterpreterViewModel by viewModels()
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
                    interpreterViewModel = interpeterViewModel
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
                interpeterViewModel.attachEngine(engine)
            }
        }
    }
}