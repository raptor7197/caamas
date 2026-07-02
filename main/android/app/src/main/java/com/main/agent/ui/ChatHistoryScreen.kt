package com.main.agent.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.main.agent.persistence.entities.SessionEntity
import com.main.agent.ui.theme.AgentInk
import com.main.agent.ui.theme.AgentInkSoft
import com.main.agent.ui.theme.AgentLine
import com.main.agent.ui.theme.AgentMist
import com.main.agent.ui.theme.AgentPaper
import com.main.agent.ui.theme.AgentPeach
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatHistoryScreen(
    viewModel: ChatViewModel = viewModel(),
    onOpenSession: () -> Unit,
    onNewChat: () -> Unit,
    onBack: () -> Unit,
) {
    val sessions by viewModel.allSessions.collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Chat History",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = AgentPaper,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = AgentPaper)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.startNewChat()
                        onNewChat()
                    }) {
                        Icon(Icons.Default.Add, "New Chat", tint = AgentPaper)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = AgentInk),
            )
        },
        containerColor = AgentPaper,
    ) { padding ->
        if (sessions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("No saved chats yet", color = AgentInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text("Start a conversation to save it here.", color = AgentInkSoft, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(sessions, key = { it.id }) { session ->
                    SessionCard(
                        session = session,
                        onClick = {
                            viewModel.switchToSession(session.id)
                            onOpenSession()
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SessionCard(session: SessionEntity, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = AgentMist,
        border = BorderStroke(1.5.dp, AgentLine),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = session.title.ifBlank { "New chat" },
                color = AgentInk,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Started ${formatDate(session.createdAt)}",
                    color = AgentInkSoft,
                    fontSize = 12.sp,
                )
                Text(
                    text = "Updated ${formatDate(session.updatedAt)}",
                    color = AgentPeach,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

private fun formatDate(timestamp: Long): String =
    SimpleDateFormat("MMM d, h:mm a", Locale.getDefault()).format(Date(timestamp))
