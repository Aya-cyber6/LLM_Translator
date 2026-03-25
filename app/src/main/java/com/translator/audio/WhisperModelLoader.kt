// WhisperModelLoader.kt
// Copies the ggml Whisper model from app assets to the device's filesDir so
// WhisperContext.createContextFromFile() can load it as a regular file path.
//
// Usage (in Activity / Fragment onCreate or a DI module):
//
//   val modelPath = WhisperModelLoader.ensureModel(context, "ggml-base.bin")
//   viewModel.loadWhisperModel(modelPath)

package com.translator.audio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object WhisperModelLoader {

    private const val TAG = "WhisperModelLoader"

    /**
     * Ensures [assetFileName] is present in [context].filesDir and returns the
     * absolute path.  The file is only copied once; subsequent calls are cheap.
     *
     * @param context        Application or Activity context.
     * @param assetFileName  File name inside `assets/models/`, e.g. `"ggml-base.bin"`.
     * @return Absolute path to the model file on the device.
     */
    suspend fun ensureModel(context: Context, assetFileName: String): String =
        withContext(Dispatchers.IO) {
            val dest = File(context.filesDir, assetFileName)
            if (!dest.exists()) {
                Log.i(TAG, "Copying $assetFileName from assets → ${dest.absolutePath}")
                context.assets.open("models/$assetFileName").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
                Log.i(TAG, "Copy complete (${dest.length()} bytes)")
            } else {
                Log.d(TAG, "Model already present: ${dest.absolutePath}")
            }
            dest.absolutePath
        }
}
