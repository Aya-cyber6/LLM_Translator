package com.translator.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.translator.ui.state.Language
import com.translator.ui.state.TranslationUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class TranslationViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(TranslationUiState())
    val uiState: StateFlow<TranslationUiState> = _uiState.asStateFlow()

    private var engine: Engine? = null
    private var translationJob: Job? = null

    // -------------------------------------------------------------------------
    // Engine lifecycle
    // -------------------------------------------------------------------------

    fun initializeEngine(modelPath: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val config = EngineConfig(
                    modelPath = modelPath,
                    backend = Backend.CPU(),
                    cacheDir = getApplication<Application>().cacheDir.path
                )
                val newEngine = Engine(config)
                newEngine.initialize()
                engine = newEngine
                _uiState.update {
                    it.copy(llmState = it.llmState.copy(isEngineReady = true, error = null))
                }
            } catch (e: Exception) {
                // FIXED: Target the nested llmState here as well
                _uiState.update {
                    it.copy(
                        llmState = it.llmState.copy(
                            isEngineReady = false,
                            error = "Engine init failed: ${e.message}"
                        )
                    )
                }
            }
        }
    }

    /**
     * Suspends until the engine is ready, then delivers it via [block].
     * Used by MainActivity to share the engine with AudioTranslationViewModel.
     */
    suspend fun waitForEngine(block: (Engine) -> Unit) {
        _uiState.first { it.llmState.isEngineReady }
        engine?.let { block(it) }
    }

    // -------------------------------------------------------------------------
    // Text-to-text translation
    // -------------------------------------------------------------------------

    fun onSourceTextChange(text: String) {
        _uiState.update {
            it.copy(
                sourceText = text,
                llmState = it.llmState.copy(error = null)
            )
        }
    }

    fun translate() {
        val state = _uiState.value
        if (!state.canTranslate) return

        translationJob?.cancel()

        translationJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    llmState = it.llmState.copy(
                        isTranslating = true,
                        translatedText = "",
                        error = null
                    )
                )
            }

            try {
                val currentEngine = engine
                    ?: throw IllegalStateException("Engine is not initialized")

                val conversationConfig = ConversationConfig(
                    systemInstruction = Contents.Companion.of(
                        buildSystemInstruction(state.sourceLanguage, state.targetLanguage)
                    ),
                    samplerConfig = SamplerConfig(
                        topK = 40,
                        topP = 0.95,
                        temperature = 0.3
                    )
                )

                withContext(Dispatchers.IO) {
                    currentEngine.createConversation(conversationConfig).use { conversation ->
                        conversation
                            .sendMessageAsync(state.sourceText)
                            .catch { e ->
                                _uiState.update {
                                    it.copy(
                                        llmState = it.llmState.copy(
                                            isTranslating = false,
                                            error = "Translation failed: ${e.message}"
                                        )
                                    )
                                }
                            }
                            .collect { message ->
                                _uiState.update { current ->
                                    current.copy(
                                        llmState = current.llmState.copy(
                                            translatedText = current.llmState.translatedText + message.toString()
                                        )
                                    )
                                }
                            }
                    }
                }

                _uiState.update {
                    it.copy(llmState = it.llmState.copy(isTranslating = false)) // FIXED
                }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        llmState = it.llmState.copy( // FIXED
                            isTranslating = false,
                            error = "Translation failed: ${e.message}"
                        )
                    )
                }
            }
        }
    }

    fun clearTranslation() {
        translationJob?.cancel()
        _uiState.update {
            it.copy(
                sourceText = "",
                llmState = it.llmState.copy(
                    translatedText = "",
                    isTranslating = false,
                    error = null
                )
            )
        }
    }

    fun copyTranslationToSource() {
        val current = _uiState.value
        if (current.llmState.translatedText.isBlank()) return
        _uiState.update {
            it.copy(
                sourceText = current.llmState.translatedText,
                sourceLanguage = current.targetLanguage,
                targetLanguage = current.sourceLanguage,
                llmState = it.llmState.copy(
                    translatedText = "",
                    error = null
                )
            )
        }
    }

    // -------------------------------------------------------------------------
    // Language selection
    // -------------------------------------------------------------------------

    fun setSourceLanguage(language: Language) {
        _uiState.update {
            if (language.code == it.targetLanguage.code)
                it.copy(sourceLanguage = language, targetLanguage = it.sourceLanguage)
            else
                it.copy(sourceLanguage = language)
        }
    }

    fun setTargetLanguage(language: Language) {
        _uiState.update {
            if (language.code == it.sourceLanguage.code)
                it.copy(targetLanguage = language, sourceLanguage = it.targetLanguage)
            else
                it.copy(targetLanguage = language)
        }
    }

    fun swapLanguages() {
        _uiState.update {
            it.copy(
                sourceLanguage = it.targetLanguage,
                targetLanguage = it.sourceLanguage,
                sourceText = it.llmState.translatedText,
                llmState = it.llmState.copy(
                    translatedText = it.sourceText
                )
            )
        }
    }

    // -------------------------------------------------------------------------
    // Prompt helpers
    // -------------------------------------------------------------------------

    private fun buildSystemInstruction(source: Language, target: Language): String =
        """
        You are a translation engine.
        Translate everything the user writes from ${source.displayName} to ${target.displayName}.
        Output only the translated text — no explanations, no alternatives, no preamble.
        """.trimIndent()

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    override fun onCleared() {
        super.onCleared()
        translationJob?.cancel()
        engine?.close()
        engine = null
    }
}