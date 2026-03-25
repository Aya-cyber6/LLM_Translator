package com.translator.screen

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.translator.ui.viewmodel.TranslationViewModel
import com.translator.ui.state.Language


@Composable
fun TextTranslationScreen(
    viewModel: TranslationViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Engine status banner
        EngineStatusBanner(state)

        // Language selector row
        LanguageSelectorRow(
            sourceLanguage = state.sourceLanguage,
            targetLanguage = state.targetLanguage,
            availableLanguages = Language.entries,
            onSourceSelected = viewModel::setSourceLanguage,
            onTargetSelected = viewModel::setTargetLanguage,
            onSwap = viewModel::swapLanguages
        )

        // Source input card
        SourceTextCard(
            text = state.sourceText,
            charCount = state.charCount,
            maxChars = state.maxChars,
            isOverLimit = state.isOverLimit,
            onTextChange = viewModel::onSourceTextChange,
            onClear = viewModel::clearTranslation
        )

        // Translate button
        TranslateButton(
            canTranslate = state.canTranslate,
            isTranslating = state.llmState.isTranslating,
            onClick = viewModel::translate
        )

        // Result card
        AnimatedVisibility(
            visible = state.llmState.translatedText.isNotBlank() || state.llmState.isTranslating || state.llmState.error != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut() + shrinkVertically()
        ) {
            ResultCard(
                translatedText = state.llmState.translatedText,
                isTranslating = state.llmState.isTranslating,
                error = state.llmState.error,
                targetLanguage = state.targetLanguage,
                onUseAsSource = viewModel::copyTranslationToSource
            )
        }

        Spacer(modifier = Modifier.height(80.dp))
    }
}

// ---------------------------------------------------------------------------
// Sub-components
// ---------------------------------------------------------------------------

@Composable
private fun EngineStatusBanner(state: com.translator.ui.state.TranslationUiState) {
    AnimatedVisibility(visible = !state.llmState.isEngineReady || state.llmState.error != null) {
        val isError = state.llmState.error != null
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = if (isError) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.secondaryContainer,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isError) {
                    Icon(Icons.Default.Warning, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error)
                    Text(
                        text = state.llmState.error ?: "Engine error",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = "Loading translation engine…",
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun LanguageSelectorRow(
    sourceLanguage: Language,
    targetLanguage: Language,
    availableLanguages: List<Language>,
    onSourceSelected: (Language) -> Unit,
    onTargetSelected: (Language) -> Unit,
    onSwap: () -> Unit
) {
    var showSourcePicker by remember { mutableStateOf(false) }
    var showTargetPicker by remember { mutableStateOf(false) }

    val swapRotation by animateFloatAsState(
        targetValue = if (showSourcePicker || showTargetPicker) 180f else 0f,
        animationSpec = tween(300),
        label = "swap_rotation"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        LanguageChip(
            language = sourceLanguage,
            onClick = { showSourcePicker = true },
            modifier = Modifier.weight(1f)
        )

        IconButton(
            onClick = onSwap,
            modifier = Modifier
                .padding(horizontal = 4.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Icon(
                Icons.Default.SwapHoriz,
                contentDescription = "Swap languages",
                modifier = Modifier.rotate(swapRotation),
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }

        LanguageChip(
            language = targetLanguage,
            onClick = { showTargetPicker = true },
            modifier = Modifier.weight(1f)
        )
    }

    if (showSourcePicker) {
        LanguagePickerDialog(
            title = "Translate from",
            languages = availableLanguages,
            selected = sourceLanguage,
            onSelect = { onSourceSelected(it); showSourcePicker = false },
            onDismiss = { showSourcePicker = false }
        )
    }
    if (showTargetPicker) {
        LanguagePickerDialog(
            title = "Translate to",
            languages = availableLanguages,
            selected = targetLanguage,
            onSelect = { onTargetSelected(it); showTargetPicker = false },
            onDismiss = { showTargetPicker = false }
        )
    }
}

@Composable
private fun LanguageChip(
    language: Language,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Text(
                text = language.displayName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LanguagePickerDialog(
    title: String,
    languages: List<Language>,
    selected: Language,
    onSelect: (Language) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(languages) { lang ->
                        val isSelected = lang.code == selected.code
                        Surface(
                            onClick = { onSelect(lang) },
                            shape = RoundedCornerShape(10.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else Color.Transparent
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        lang.displayName,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                    Text(
                                        lang.nativeName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceTextCard(
    text: String,
    charCount: Int,
    maxChars: Int,
    isOverLimit: Boolean,
    onTextChange: (String) -> Unit,
    onClear: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            BasicTextField(
                value = text,
                onValueChange = onTextChange,
                textStyle = TextStyle(
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 16.sp,
                    lineHeight = 24.sp
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                decorationBox = { inner ->
                    if (text.isEmpty()) {
                        Text(
                            "Enter text to translate…",
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            style = TextStyle(fontSize = 16.sp, lineHeight = 24.sp)
                        )
                    }
                    inner()
                }
            )

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            Spacer(Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$charCount / $maxChars",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isOverLimit) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (text.isNotBlank()) {
                    TextButton(
                        onClick = onClear,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Clear", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun TranslateButton(
    canTranslate: Boolean,
    isTranslating: Boolean,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        enabled = canTranslate,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        shape = RoundedCornerShape(14.dp)
    ) {
        if (isTranslating) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(10.dp))
            Text("Translating…")
        } else {
            Icon(Icons.Default.Translate, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("Translate", fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun ResultCard(
    translatedText: String,
    isTranslating: Boolean,
    error: String?,
    targetLanguage: Language,
    onUseAsSource: () -> Unit
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = targetLanguage.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(Modifier.height(8.dp))

            when {
                error != null -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
                isTranslating -> {
                    // Pulsing skeleton lines
                    val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
                        initialValue = 0.3f, targetValue = 0.8f,
                        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                        label = "alpha"
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        repeat(3) { i ->
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(if (i == 2) 0.6f else 1f)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(
                                        MaterialTheme.colorScheme.onSurface.copy(alpha = alpha * 0.15f)
                                    )
                            )
                        }
                    }
                }
                else -> {
                    Text(
                        text = translatedText,
                        style = MaterialTheme.typography.bodyLarge,
                        lineHeight = 26.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    Spacer(Modifier.height(12.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.2f))
                    Spacer(Modifier.height(6.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        // Copy button
                        TextButton(
                            onClick = {
                                clipboard.setText(AnnotatedString(translatedText))
                                copied = true
                            },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (copied) "Copied!" else "Copy",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                        // Use as new source
                        TextButton(
                            onClick = onUseAsSource,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Use as source", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
}
