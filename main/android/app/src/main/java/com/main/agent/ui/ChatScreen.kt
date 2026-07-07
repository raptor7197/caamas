package com.main.agent.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.main.agent.agent.Route
import com.main.agent.persistence.entities.MessageEntity
import com.main.agent.ui.theme.AgentBlue
import com.main.agent.ui.theme.AgentInk
import com.main.agent.ui.theme.AgentInkSoft
import com.main.agent.ui.theme.AgentLine
import com.main.agent.ui.theme.AgentMist
import com.main.agent.ui.theme.AgentPaper
import com.main.agent.ui.theme.AgentPeach
import com.main.agent.ui.theme.AgentPeachDeep
import com.main.agent.ui.theme.AgentSurface2
import com.main.agent.ui.theme.ToolChip
import com.main.agent.voice.VoicePipeline
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel:      ChatViewModel = viewModel(),
    onOpenSettings: () -> Unit,
    onOpenHistory:  () -> Unit = {},
) {
    val uiState    by viewModel.uiState.collectAsState()
    val listState  = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var inputText  by remember { mutableStateOf("") }
    var showStats  by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.messages.size, uiState.streamingText) {
        if (uiState.messages.isNotEmpty() || uiState.streamingText.isNotEmpty()) {
            listState.animateScrollToItem(
                if (uiState.streamingText.isNotEmpty()) Int.MAX_VALUE
                else maxOf(0, uiState.messages.size - 1)
            )
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            BlobMark(
                                modifier = Modifier.size(38.dp),
                                ink = AgentPaper,
                                paper = AgentInk,
                                accent = AgentPeach,
                            )
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "CAAMAS",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AgentPaper,
                                )
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    var expanded by remember { mutableStateOf(false) }
                                    Box {
                                        Surface(
                                            modifier = Modifier.clickable { expanded = true },
                                            shape = RoundedCornerShape(50),
                                            color = AgentPaper,
                                            border = BorderStroke(2.dp, AgentLine),
                                        ) {
                                            Row(
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                            ) {
                                                Text(
                                                    viewModel.getRouteLabel(uiState.selectedRoute),
                                                    color = AgentInk,
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                )
                                                Icon(
                                                    Icons.Default.ArrowDropDown,
                                                    "Select model",
                                                    tint = AgentInk,
                                                    modifier = Modifier.size(18.dp),
                                                )
                                            }
                                        }
                                        DropdownMenu(
                                            expanded = expanded,
                                            onDismissRequest = { expanded = false },
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Auto") },
                                                onClick = { viewModel.setSelectedRoute(null); expanded = false },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Small (Local)") },
                                                onClick = { viewModel.setSelectedRoute(Route.LocalSmall); expanded = false },
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Large (Local)") },
                                                onClick = { viewModel.setSelectedRoute(Route.LocalLarge); expanded = false },
                                            )
                                            uiState.availableRoutes.forEach { route ->
                                                if (route is Route.Cloud) {
                                                    DropdownMenuItem(
                                                        text = { Text(route.provider.name) },
                                                        onClick = { viewModel.setSelectedRoute(route); expanded = false },
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        modifier = Modifier.clickable { showStats = !showStats },
                                        text = if (showStats) "Hide stats" else "Show stats",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = AgentPeach,
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        IconButton(onClick = onOpenHistory) {
                            Icon(Icons.Default.History, "History", tint = AgentPaper)
                        }
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, "Settings", tint = AgentPaper)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = AgentInk,
                        titleContentColor = AgentPaper,
                        actionIconContentColor = AgentPaper,
                    )
                )
                AnimatedVisibility(visible = showStats) {
                    uiState.modelStats?.let { stats ->
                        ModelStatsHeader(stats = stats, cpuLoad = uiState.cpuLoad)
                    }
                }
            }
        },
        containerColor = AgentPaper,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                state    = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                if (uiState.messages.none { it.role != "system" } && uiState.streamingText.isEmpty()) {
                    item { EmptyChatState() }
                }

                items(uiState.messages.filter { it.role != "system" }) { msg ->
                    MessageBubble(msg)
                }

                if (uiState.activeToolCalls.isNotEmpty()) {
                    item {
                        Column(
                            modifier = Modifier.padding(start = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            uiState.activeToolCalls.forEach { call ->
                                ToolCallChip(name = call.name, done = call.done, isError = call.isError)
                            }
                        }
                    }
                }

                if (uiState.streamingText.isNotEmpty()) {
                    item { StreamingBubble(text = uiState.streamingText) }
                }

                if (uiState.isGenerating && uiState.streamingText.isEmpty() && uiState.activeToolCalls.isEmpty()) {
                    item { ThinkingIndicator() }
                }
            }

            uiState.error?.let { err ->
                Surface(
                    color    = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    shape    = RoundedCornerShape(18.dp),
                    border   = BorderStroke(2.dp, AgentLine),
                ) {
                    Row(
                        Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(err, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer, fontSize = 13.sp)
                        TextButton(onClick = viewModel::clearError) { Text("Dismiss") }
                    }
                }
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = AgentInk,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value            = inputText,
                        onValueChange    = { if (it.length <= 4000) inputText = it },
                        modifier         = Modifier.weight(1f),
                        placeholder      = { Text("Ask anything...", fontSize = 14.sp) },
                        maxLines         = 5,
                        keyboardOptions  = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions  = KeyboardActions(onSend = {
                            if (inputText.isNotBlank() && !uiState.isGenerating) {
                                viewModel.sendMessage(inputText.trim())
                                inputText = ""
                            }
                        }),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = AgentInk,
                            unfocusedTextColor = AgentInk,
                            focusedContainerColor = AgentPaper,
                            unfocusedContainerColor = AgentPaper,
                            disabledContainerColor = AgentMist,
                            cursorColor = AgentPeachDeep,
                            focusedBorderColor = AgentPeach,
                            unfocusedBorderColor = AgentPaper,
                            focusedPlaceholderColor = AgentInkSoft.copy(alpha = 0.55f),
                            unfocusedPlaceholderColor = AgentInkSoft.copy(alpha = 0.55f),
                        ),
                        shape = RoundedCornerShape(22.dp),
                        enabled = !uiState.isGenerating,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (uiState.isGenerating) {
                        FilledIconButton(
                            onClick = { viewModel.cancelGeneration() },
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = AgentPaper,
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Icon(Icons.Default.Stop, "Stop")
                        }
                    } else {
                        val isListening = uiState.voiceState is VoicePipeline.State.Listening
                        FilledIconButton(
                            onClick = {
                                if (isListening) {
                                    coroutineScope.launch {
                                        viewModel.stopVoiceListening()?.let { transcribed ->
                                            inputText = transcribed
                                        }
                                    }
                                } else {
                                    viewModel.startVoiceListening()
                                }
                            },
                            enabled = !uiState.isGenerating,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = AgentPaper,
                                contentColor = if (isListening) MaterialTheme.colorScheme.error else AgentInk,
                            ),
                        ) {
                            Icon(
                                if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                                if (isListening) "Stop" else "Voice",
                            )
                        }
                        FilledIconButton(
                            onClick = {
                                if (inputText.isNotBlank()) {
                                    viewModel.sendMessage(inputText.trim())
                                    inputText = ""
                                }
                            },
                            enabled = inputText.isNotBlank() && !uiState.isGenerating,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = if (inputText.isNotBlank()) AgentPeach else AgentMist,
                                contentColor = AgentInk,
                                disabledContainerColor = ToolChip,
                                disabledContentColor = AgentInkSoft.copy(alpha = 0.45f),
                            ),
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Send")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyChatState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 56.dp, start = 20.dp, end = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BlobMark(Modifier.size(112.dp))
        Text(
            "Ready when you are",
            color = AgentInk,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
        )
        Text(
            "Ask a question, dictate a task, or connect a local model.",
            color = AgentInkSoft,
            fontSize = 14.sp,
            lineHeight = 20.sp,
        )
    }
}

@Composable
private fun MessageBubble(msg: MessageEntity) {
    val isUser = msg.role == "user"
    val isTool = msg.role == "tool"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(
                topStart = 18.dp, topEnd = 18.dp,
                bottomStart = if (isUser) 18.dp else 6.dp,
                bottomEnd   = if (isUser) 6.dp  else 18.dp,
            ),
            color = when {
                isUser -> AgentPeach
                isTool -> AgentMist
                else   -> AgentPaper
            },
            border = BorderStroke(2.dp, AgentLine),
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                text     = msg.content,
                color    = AgentInk,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun ToolCallChip(name: String, done: Boolean, isError: Boolean) {
    Surface(
        shape  = RoundedCornerShape(50),
        color  = ToolChip,
        border = BorderStroke(1.dp, AgentLine),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            when {
                !done    -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = AgentPeachDeep)
                isError  -> Icon(Icons.Default.Close, null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.error)
                else     -> Icon(Icons.Default.Check, null, Modifier.size(12.dp), tint = AgentInk)
            }
            Text(name, fontSize = 12.sp, color = AgentInk, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun StreamingBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Surface(
            modifier = Modifier.widthIn(max = 300.dp),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 6.dp, bottomStart = 18.dp),
            color = AgentPaper,
            border = BorderStroke(2.dp, AgentLine),
        ) {
            Text(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                text = text + "\u258C",
                color = AgentInk,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun ModelStatsHeader(stats: ModelStats, cpuLoad: Float) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = AgentSurface2,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem("Model", stats.modelName)
                StatItem("Context", "${stats.contextSize}")
                StatItem("Threads", "${stats.threads}")
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatItem("RAM", "${stats.ramUsedMb} / ${stats.ramTotalMb} MB")
                StatItem("CPU", "%.0f%%".format(cpuLoad))
                StatItem("Vulkan", if (stats.hasVulkan) "Yes" else "No")
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = AgentPeach)
        Text(value, style = MaterialTheme.typography.bodySmall, color = AgentPaper)
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(
        modifier = Modifier.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AgentPeachDeep)
        Text("Thinking...", fontSize = 13.sp, color = AgentInkSoft)
    }
}
