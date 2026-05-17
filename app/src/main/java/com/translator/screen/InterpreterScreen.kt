package com.translator.screen


import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.translator.ui.state.InterpreterEntry
import com.translator.ui.state.Language
import com.translator.ui.state.ModelStatus
import com.translator.ui.state.PickerTarget
import com.translator.ui.viewmodel.InterpreterViewModel

// ---------------------------------------------------------------------------
// Root screen
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InterpreterScreen(
    viewModel: InterpreterViewModel,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    // Auto-scroll the history log to the latest entry whenever a new one arrives
    LaunchedEffect(state.history.size) {
        if (state.history.isNotEmpty()) {
            listState.animateScrollToItem(state.history.lastIndex)
        }
    }

    // Language picker bottom sheet (controlled entirely by ViewModel state)
    if (state.isPickerOpen) {
        InterpreterLanguagePickerSheet(
            title     = if (state.pickerTarget == PickerTarget.SOURCE) "Listening language" else "Speaking language",
            languages = Language.entries,
            selected  = if (state.pickerTarget == PickerTarget.SOURCE)
                state.sourceLanguage else state.targetLanguage,
            onSelect  = viewModel::selectLanguage,
            onDismiss = viewModel::dismissPicker,
        )
    }

    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {

        // ── Top section: language bar + model status + errors ─────────────────
        Column(
            modifier            = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text  = "Interpreter Mode",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )

            InterpreterLanguageBar(
                source        = state.sourceLanguage,
                target        = state.targetLanguage,
                enabled       = !state.isBusy,
                onSourceClick = viewModel::openSourcePicker,
                onTargetClick = viewModel::openTargetPicker,
                onSwap        = viewModel::swapLanguages,
            )

            // Model status row
            when (state.modelStatus) {
                ModelStatus.CHECKING -> {
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text("Checking speech model…", style = MaterialTheme.typography.bodySmall)
                    }
                }
                ModelStatus.DOWNLOADING -> {
                    Row(
                        verticalAlignment     = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        Text(
                            state.downloadProgress ?: "Downloading model…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                ModelStatus.DOWNLOADABLE -> {
                    OutlinedButton(
                        onClick  = viewModel::downloadASRModel,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download speech model")
                    }
                }
                ModelStatus.UNAVAILABLE ->
                    InterpreterErrorBanner("Speech recognition unavailable on this device.")
                ModelStatus.AVAILABLE -> { /* mic button becomes active */ }
            }

            state.modelError?.let       { InterpreterErrorBanner(it) }
            state.translationError?.let { InterpreterErrorBanner(it) }
        }

        // ── Middle: conversation log ──────────────────────────────────────────
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            if (state.history.isEmpty() && !state.isListening) {
                // Empty state placeholder
                Column(
                    modifier            = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text      = "Press Start to begin interpreting",
                        style     = MaterialTheme.typography.bodyMedium,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        text      = "Everything spoken aloud will be\ntranslated and read back to you.",
                        style     = MaterialTheme.typography.bodySmall,
                        color     = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                    )
                }
            } else {
                LazyColumn(
                    state           = listState,
                    contentPadding  = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier        = Modifier.fillMaxSize(),
                ) {
                    items(state.history, key = { it.id }) { entry ->
                        Column {
                            AnimatedVisibility(
                                visible  = true,
                                enter    = fadeIn() + slideInVertically { it / 2 },
                            ) {
                                InterpreterEntryCard(entry = entry, targetLanguage = state.targetLanguage)
                            }
                        }
                    }

                    // Live caption appended below committed history
                    if (state.showLiveCaption) {
                        item(key = "live_caption") {
                            LiveCaptionRow(
                                text     = state.liveCaption,
                                language = state.sourceLanguage,
                            )
                        }
                    }
                }
            }

            // Fade mask at the top of the list so text disappears softly
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(24.dp)
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.background,
                                Color.Transparent,
                            )
                        )
                    )
            )
        }

        // ── Bottom: status label + big mic button ─────────────────────────────
        Column(
            modifier            = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Status description underneath the button
            Text(
                text = when {
                    state.isListening && state.isTranslating -> "Translating…"
                    state.isListening                        -> "Listening…"
                    state.modelStatus != ModelStatus.AVAILABLE -> "Model not ready"
                    !state.isEngineReady                     -> "Engine loading…"
                    !state.isTtsReady                        -> "TTS loading…"
                    else                                     -> "Ready"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            InterpreterMicButton(
                isListening = state.isListening,
                enabled     = state.canStart || state.canStop,
                onStart     = viewModel::startListening,
                onStop      = viewModel::stopListening,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Conversation log entry
// ---------------------------------------------------------------------------

@Composable
private fun InterpreterEntryCard(
    entry: InterpreterEntry,
    targetLanguage: Language,
) {
    Column(
        modifier            = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Original text (subdued)
        Text(
            text      = entry.original,
            style     = MaterialTheme.typography.bodySmall,
            color     = MaterialTheme.colorScheme.onSurfaceVariant,
            fontStyle = FontStyle.Italic,
        )

        // Translation (prominent)
        Card(
            shape  = RoundedCornerShape(topStart = 4.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
            ),
        ) {
            Text(
                text     = entry.translated,
                style    = MaterialTheme.typography.bodyLarge,
                color    = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Live caption row (partial ASR text)
// ---------------------------------------------------------------------------

@Composable
private fun LiveCaptionRow(text: String, language: Language) {
    val infiniteTransition = rememberInfiniteTransition(label = "blink")
    val alpha by infiniteTransition.animateFloat(
        initialValue  = 1f,
        targetValue   = 0.3f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing), RepeatMode.Reverse),
        label         = "alpha",
    )

    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier              = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    MaterialTheme.colorScheme.error.copy(alpha = alpha),
                    shape = CircleShape,
                )
        )
        Text(
            text  = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        )
    }
}

// ---------------------------------------------------------------------------
// Language bar
// ---------------------------------------------------------------------------

@Composable
private fun InterpreterLanguageBar(
    source: Language,
    target: Language,
    enabled: Boolean,
    onSourceClick: () -> Unit,
    onTargetClick: () -> Unit,
    onSwap: () -> Unit,
) {
    Row(
        verticalAlignment     = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier              = Modifier.fillMaxWidth(),
    ) {
        InterpreterLangChip(
            language = source,
            label    = "Listening",
            enabled  = enabled,
            onClick  = onSourceClick,
            modifier = Modifier.weight(1f),
        )

        IconButton(onClick = onSwap, enabled = enabled) {
            Icon(Icons.Default.SwapHoriz, contentDescription = "Swap languages")
        }

        InterpreterLangChip(
            language = target,
            label    = "Speaking",
            enabled  = enabled,
            onClick  = onTargetClick,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun InterpreterLangChip(
    language: Language,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape   = RoundedCornerShape(10.dp),
        color   = MaterialTheme.colorScheme.secondaryContainer,
        modifier = modifier
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Column(
            modifier            = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text  = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )
            Text(
                text      = language.displayName,
                style     = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color     = if (enabled)
                    MaterialTheme.colorScheme.onSecondaryContainer
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Big mic button
// ---------------------------------------------------------------------------

@Composable
private fun InterpreterMicButton(
    isListening: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue  = 1f,
        targetValue   = 1.18f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
        label         = "scale",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        FloatingActionButton(
            onClick        = if (isListening) onStop else onStart,
            modifier       = Modifier
                .size(80.dp)
                .then(if (isListening) Modifier.scale(scale) else Modifier),
            shape          = CircleShape,
            containerColor = when {
                !enabled    -> MaterialTheme.colorScheme.surfaceVariant
                isListening -> MaterialTheme.colorScheme.error
                else        -> MaterialTheme.colorScheme.primary
            },
            contentColor   = Color.White,
        ) {
            Icon(
                imageVector        = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (isListening) "Stop interpreter" else "Start interpreter",
                modifier           = Modifier.size(36.dp),
            )
        }

        Text(
            text  = if (isListening) "Tap to stop" else "Start Listening",
            style = MaterialTheme.typography.labelMedium,
            color = if (isListening)
                MaterialTheme.colorScheme.error
            else
                MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

// ---------------------------------------------------------------------------
// Language picker bottom sheet (reused pattern from AudioTranslationScreen)
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InterpreterLanguagePickerSheet(
    title: String,
    languages: List<Language>,
    selected: Language,
    onSelect: (Language) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            text     = title,
            style    = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        LazyColumn(
            modifier       = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            items(languages) { lang ->
                ListItem(
                    headlineContent  = { Text(lang.displayName) },
                    trailingContent  = {
                        if (lang == selected) {
                            Text("✓", color = MaterialTheme.colorScheme.primary)
                        }
                    },
                    modifier = Modifier.clickable { onSelect(lang) },
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Error banner
// ---------------------------------------------------------------------------

@Composable
private fun InterpreterErrorBanner(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors   = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Text(
            text     = message,
            modifier = Modifier.padding(12.dp),
            color    = MaterialTheme.colorScheme.onErrorContainer,
            style    = MaterialTheme.typography.bodySmall,
        )
    }
}
