package com.translator.screen

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import com.translator.ui.state.Language
import com.translator.ui.state.PlaybackState
import com.translator.ui.state.RecordingState
import com.translator.ui.viewmodel.AudioTranslationViewModel

@Composable
fun AudioTranslationScreen(
    viewModel: AudioTranslationViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    // Helper variable to replace the old state.isTranscribing
    val isTranscribing = state.recordingState != RecordingState.IDLE

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {

        Text(
            "Voice Translator",
            style = MaterialTheme.typography.headlineMedium,
        )

        // ---- Model loading indicators ----
        if (state.asrState.isLoading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Loading Whisper model…", style = MaterialTheme.typography.bodySmall)
            }
        }

        // Consolidated Error Banners
        state.asrState.error?.let { ErrorBanner("Audio/ASR Error: $it") }
        state.llmState.error?.let { ErrorBanner("Translation Error: $it") }
        state.ttsState.error?.let { ErrorBanner("Playback Error: $it") }

        // ---- Language pair row ----
        LanguagePairRow(
            source   = state.sourceLanguage,
            target   = state.targetLanguage,
            onSwap   = viewModel::swapLanguages,
            onSource = viewModel::setSourceLanguage,
            onTarget = viewModel::setTargetLanguage,
        )

        // ---- Record button ----
        RecordButton(
            recordingState = state.recordingState,
            enabled        = state.canRecord || state.canStopRecording,
            onStart        = viewModel::startRecording,
            onStop         = viewModel::stopRecording,
            onCancel       = viewModel::cancelRecording,
        )

        // ---- Live captions (shown while recording) ----
        AnimatedVisibility(isTranscribing && state.asrState.sourceTranscript.isNotBlank()) {
            TranscriptCard(
                label = "Listening…",
                text  = state.asrState.sourceTranscript,
                isLive = true,
            )
        }

        // ---- Final transcript ----
        AnimatedVisibility(
            !isTranscribing && state.asrState.sourceTranscript.isNotBlank()
        ) {
            TranscriptCard(
                label  = "You said (${state.sourceLanguage.displayName})",
                text   = state.asrState.sourceTranscript,
                isLive = false,
            )
        }

        // ---- Translation output ----
        AnimatedVisibility(state.llmState.translatedText.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        verticalAlignment   = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Translation (${state.targetLanguage.displayName})",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        IconButton(
                            onClick  = viewModel::speakTranslation,
                            enabled  = state.ttsState.isReady &&
                                    state.playbackState == PlaybackState.IDLE,
                        ) {
                            Icon(
                                Icons.Default.VolumeUp,
                                contentDescription = "Speak translation",
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text  = state.llmState.translatedText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    if (state.recordingState == RecordingState.PROCESSING || state.llmState.isTranslating) {
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

@Composable
private fun RecordButton(
    recordingState: RecordingState,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCancel: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue  = 1.15f,
        animationSpec = infiniteRepeatable(
            animation  = tween(600),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "scale",
    )

    val isRecording = recordingState == RecordingState.RECORDING

    Row(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment     = Alignment.CenterVertically,
    ) {
        FloatingActionButton(
            onClick = if (isRecording) onStop else onStart,
            modifier = Modifier
                .size(72.dp)
                .then(if (isRecording) Modifier.scale(scale) else Modifier),
            shape            = CircleShape,
            containerColor   = if (isRecording)
                MaterialTheme.colorScheme.error
            else
                MaterialTheme.colorScheme.primary,
            contentColor     = Color.White,
        ) {
            Icon(
                imageVector         = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription  = if (isRecording) "Stop recording" else "Start recording",
                modifier            = Modifier.size(32.dp),
            )
        }

        if (isRecording) {
            TextButton(onClick = onCancel) {
                Icon(Icons.Default.MicOff, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("Cancel")
            }
        }
    }

    Text(
        text  = when (recordingState) {
            RecordingState.IDLE       -> if (enabled) "Tap to speak" else "Loading…"
            RecordingState.RECORDING  -> "Recording — tap to stop"
            RecordingState.PROCESSING -> "Processing…"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun TranscriptCard(
    label: String,
    text: String,
    isLive: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                fontStyle = if (isLive) FontStyle.Italic else FontStyle.Normal,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
    }
}

@Composable
private fun ErrorBanner(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Text(
            message,
            modifier = Modifier.padding(12.dp),
            color    = MaterialTheme.colorScheme.onErrorContainer,
            style    = MaterialTheme.typography.bodySmall,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguagePairRow(
    source: Language,
    target: Language,
    onSwap: () -> Unit,
    onSource: (Language) -> Unit,
    onTarget: (Language) -> Unit,
) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        LanguageDropdown(
            selected   = source,
            label      = "From",
            onSelected = onSource,
            modifier   = Modifier.weight(1f),
        )

        TextButton(onClick = onSwap) { Text("⇄") }

        LanguageDropdown(
            selected   = target,
            label      = "To",
            onSelected = onTarget,
            modifier   = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    selected: Language,
    label: String,
    onSelected: (Language) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded        = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier        = modifier,
    ) {
        OutlinedTextField(
            value           = selected.displayName,
            onValueChange   = {},
            readOnly        = true,
            label           = { Text(label) },
            trailingIcon    = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier        = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded        = expanded,
            onDismissRequest = { expanded = false },
        ) {
            // Updated to use the new Enum entries instead of LanguageRepository
            Language.entries.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(lang.displayName) },
                    onClick = {
                        onSelected(lang)
                        expanded = false
                    },
                )
            }
        }
    }
}