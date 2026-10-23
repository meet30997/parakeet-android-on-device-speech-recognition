package com.example.offlinetranscription.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.offlinetranscription.theme.CyanAccent
import com.example.offlinetranscription.theme.NvidiaGreen
import com.example.offlinetranscription.theme.StatusWarning
import com.example.offlinetranscription.ui.components.InterimHypothesisCard
import com.example.offlinetranscription.ui.components.ModelDownloadCard
import com.example.offlinetranscription.ui.components.PerformanceRow
import com.example.offlinetranscription.ui.components.TranscriptCard
import com.example.offlinetranscription.ui.components.WaveformVisualizer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TranscriptionScreen(
    viewModel: TranscriptionViewModel,
    hasRecordPermission: Boolean,
    onRequestRecordPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val listState = rememberLazyListState()

    // SAF file picker for importing local .gguf models
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importLocalModel(uri)
        }
    }

    // Auto-scroll to latest turn or interim hypothesis
    LaunchedEffect(state.completedTurns.size, state.interimHypothesis) {
        val totalItems = state.completedTurns.size + (if (state.interimHypothesis.isNotBlank()) 1 else 0)
        if (totalItems > 0) {
            listState.animateScrollToItem(totalItems - 1)
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "NeMo Speech",
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = NvidiaGreen
                                )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "TDT 0.6B",
                                style = MaterialTheme.typography.titleSmall.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            )
                        }
                        Text(
                            text = "On-Device CPU (ARM64 NEON)",
                            style = MaterialTheme.typography.labelSmall.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                },
                actions = {
                    // Thread Selector Dropdown
                    var threadMenuExpanded by remember { mutableStateOf(false) }
                    Box {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                                .clickable { threadMenuExpanded = true }
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "${state.threadCount} Threads",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontWeight = FontWeight.Bold,
                                    color = CyanAccent
                                )
                            )
                        }
                        DropdownMenu(
                            expanded = threadMenuExpanded,
                            onDismissRequest = { threadMenuExpanded = false }
                        ) {
                            listOf(2, 4, 6, 8).forEach { count ->
                                DropdownMenuItem(
                                    text = { Text("$count CPU Threads") },
                                    onClick = {
                                        viewModel.setThreadCount(count)
                                        threadMenuExpanded = false
                                    }
                                )
                            }
                        }
                    }

                    IconButton(
                        onClick = { viewModel.clearTranscripts() },
                        enabled = state.completedTurns.isNotEmpty() || state.interimHypothesis.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.Default.ClearAll,
                            contentDescription = "Clear transcripts",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 6.dp
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Real-time Waveform Canvas
                    WaveformVisualizer(
                        rmsLevel = state.rmsLevel,
                        isRecording = state.isRecording,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )

                    // Controls & Quick Actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        // Copy All Transcripts Action
                        IconButton(
                            onClick = {
                                val fullText = state.completedTurns.joinToString("\n") { it.text }
                                if (fullText.isNotBlank()) {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Full Transcript", fullText))
                                    Toast.makeText(context, "All turns copied", Toast.LENGTH_SHORT).show()
                                }
                            },
                            enabled = state.completedTurns.isNotEmpty()
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "Copy all",
                                tint = if (state.completedTurns.isNotEmpty()) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                            )
                        }

                        // Primary Record FAB
                        val fabColor = if (state.isRecording) MaterialTheme.colorScheme.error else NvidiaGreen
                        val fabIconColor = if (state.isRecording) Color.White else Color.Black

                        FloatingActionButton(
                            onClick = {
                                if (!hasRecordPermission) {
                                    onRequestRecordPermission()
                                } else {
                                    if (!state.isModelReady) {
                                        Toast.makeText(context, "Please download or load the model first", Toast.LENGTH_SHORT).show()
                                    } else {
                                        viewModel.toggleRecording()
                                    }
                                }
                            },
                            containerColor = fabColor,
                            contentColor = fabIconColor,
                            shape = CircleShape,
                            elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 8.dp),
                            modifier = Modifier.size(68.dp)
                        ) {
                            Icon(
                                imageVector = if (state.isRecording) Icons.Default.MicOff else Icons.Default.Mic,
                                contentDescription = if (state.isRecording) "Stop Recording" else "Start Live Speech",
                                modifier = Modifier.size(32.dp)
                            )
                        }

                        // RTF Indicator or Spacer
                        Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                            if (state.latestRtf > 0.001f) {
                                Text(
                                    text = String.format(java.util.Locale.US, "%.2fx", state.latestRtf),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp,
                                        color = CyanAccent
                                    )
                                )
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            // Diagnostics & Status
            PerformanceRow(
                rtf = state.latestRtf,
                latencyMs = state.latestLatencyMs,
                threads = state.threadCount,
                modifier = Modifier.padding(vertical = 4.dp)
            )

            // Status message banner
            AnimatedVisibility(
                visible = state.statusMessage != null || state.isBenchmarkRunning,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f))
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (state.isBenchmarkRunning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = CyanAccent
                            )
                        }
                        Text(
                            text = state.statusMessage ?: "Running inference...",
                            style = MaterialTheme.typography.bodySmall.copy(
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Model Download & Status Card
            ModelDownloadCard(
                isModelReady = state.isModelReady,
                modelSizeMb = state.modelSizeMb,
                downloadState = state.downloadState,
                onStartDownload = { viewModel.startModelDownload() },
                onPickLocalModel = { filePickerLauncher.launch(arrayOf("*/*")) },
                onTestJfkSample = { viewModel.runJfkBenchmark() },
                modifier = Modifier.padding(vertical = 4.dp)
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Transcripts History List
            if (state.completedTurns.isEmpty() && state.interimHypothesis.isBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.RecordVoiceOver,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f),
                            modifier = Modifier.size(48.dp)
                        )
                        Text(
                            text = if (state.isModelReady) "Ready to transcribe.\nTap the microphone and start speaking." else "Download or load the model to begin.",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    items(state.completedTurns, key = { it.id }) { turn ->
                        TranscriptCard(turn = turn)
                    }

                    if (state.interimHypothesis.isNotBlank()) {
                        item {
                            InterimHypothesisCard(
                                text = state.interimHypothesis,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
