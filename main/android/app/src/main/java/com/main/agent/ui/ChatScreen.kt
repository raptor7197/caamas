package com.main.agent.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.main.agent.persistence.entities.MessageEntity
import com.main.agent.ui.theme.AgentSurface2
import com.main.agent.ui.theme.AgentBlue
import com.main.agent.ui.theme.ToolChip
import com.main.agent.voice.VoicePipeline
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel:     ChatViewModel = viewModel(),
    onOpenSettings: () -> Unit,
) {
    val uiState    by viewModel.uiState.collectAsState()
    val listState  = rememberLazyListState()
    val scope      = rememberCoroutineScope()
    var inputText  by remember { mutableStateOf("") }

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
            TopAppBar(
                title = { Text("Agent", color = MaterialTheme.colorScheme.onBackground) },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
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
                contentPadding = PaddingValues(vertical = 8.dp),
            ) {
                items(uiState.messages.filter { it.role != "system" }) { msg ->
                    MessageBubble(msg)
                }

                if (uiState.streamingText.isNotEmpty()) {
                    item { StreamingBubble(text = uiState.streamingText) }
                }

                if (uiState.isGenerating && uiState.streamingText.isEmpty()) {
                    item { ThinkingIndicator() }
                }
            }

            uiState.error?.let { err ->
                Surface(
                    color    = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    shape    = RoundedCornerShape(8.dp),
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AgentSurface2)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value            = inputText,
                    onValueChange    = { if (it.length <= 4000) inputText = it },
                    modifier         = Modifier.weight(1f),
                    placeholder      = { Text("Ask anything\u2026", fontSize = 14.sp) },
                    maxLines         = 5,
                    keyboardOptions  = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions  = KeyboardActions(onSend = {
                        if (inputText.isNotBlank() && !uiState.isGenerating) {
                            viewModel.sendMessage(inputText.trim())
                            inputText = ""
                        }
                    }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor   = AgentBlue,
                        unfocusedBorderColor = ToolChip,
                    ),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !uiState.isGenerating,
                )
                Spacer(Modifier.width(8.dp))
                if (uiState.isGenerating) {
                    IconButton(onClick = { viewModel.cancelGeneration() }) {
                        Icon(Icons.Default.Stop, "Stop", tint = MaterialTheme.colorScheme.error)
                    }
                } else {
                    val isListening = uiState.voiceState is VoicePipeline.State.Listening
                    IconButton(
                        onClick = {
                            if (isListening) {
                                viewModel.cancelGeneration()
                            } else {
                                viewModel.startVoiceListening()
                            }
                        },
                        enabled = !uiState.isGenerating,
                    ) {
                        Icon(
                            if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                            if (isListening) "Stop" else "Voice",
                            tint = if (isListening) MaterialTheme.colorScheme.error else AgentBlue
                        )
                    }
                    IconButton(
                        onClick = {
                            if (inputText.isNotBlank()) {
                                viewModel.sendMessage(inputText.trim())
                                inputText = ""
                            }
                        },
                        enabled = inputText.isNotBlank() && !uiState.isGenerating,
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Send, "Send",
                            tint = if (inputText.isNotBlank()) AgentBlue else ToolChip)
                    }
                }
            }
        }
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
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(
                    topStart = 16.dp, topEnd = 16.dp,
                    bottomStart = if (isUser) 16.dp else 4.dp,
                    bottomEnd   = if (isUser) 4.dp  else 16.dp,
                ))
                .background(
                    when {
                        isUser -> AgentBlue
                        isTool -> ToolChip
                        else   -> AgentSurface2
                    }
                )
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(
                text     = msg.content,
                color    = MaterialTheme.colorScheme.onSurface,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            )
        }
    }
}

@Composable
private fun StreamingBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        Box(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomEnd = 4.dp, bottomStart = 16.dp))
                .background(AgentSurface2)
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(text + "\u258C", color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp)
        }
    }
}

@Composable
private fun ThinkingIndicator() {
    Row(
        modifier = Modifier.padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = AgentBlue)
        Text("Thinking\u2026", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    }
}
