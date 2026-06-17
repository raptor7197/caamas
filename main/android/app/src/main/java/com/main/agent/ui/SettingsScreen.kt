package com.main.agent.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.main.agent.preferences.UserPreferences
import com.main.agent.ui.theme.AgentBlue
import com.main.agent.ui.theme.AgentSurface2
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    prefs:    UserPreferences,
    onBack:   () -> Unit,
) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    val settings by prefs.settingsFlow.collectAsState(initial = UserPreferences.Settings())

    var showOpenAIKey    by remember { mutableStateOf(false) }
    var showAnthropicKey by remember { mutableStateOf(false) }
    var showMistralKey   by remember { mutableStateOf(false) }
    var showGeminiKey    by remember { mutableStateOf(false) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            scope.launch { prefs.setAgentFolderUri(uri.toString()) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {

            // ── Agent Folder ──────────────────────────────────────────────────
            SettingsSection("Agent Folder") {
                val folderText = if (settings.agentFolderUri.isBlank()) "Not set" else "Folder selected"
                SettingsRow(
                    icon  = Icons.Default.Folder,
                    label = "Storage folder",
                    value = folderText,
                    onClick = { folderPicker.launch(null) },
                )
                Text(
                    "Files you place here will be indexed and available to the assistant.",
                    fontSize = 12.sp,
                    color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(start = 4.dp),
                )
            }

            // ── Cloud Fallback ────────────────────────────────────────────────
            SettingsSection("Cloud Fallback (optional)") {
                Text(
                    "API keys are stored locally and never uploaded.",
                    fontSize = 12.sp,
                    color    = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                )
                Spacer(Modifier.height(8.dp))
                var providerExpanded by remember { mutableStateOf(false) }
                val providers = listOf(
                    "" to "None",
                    "openai" to "OpenAI",
                    "anthropic" to "Anthropic",
                    "mistral" to "Mistral",
                    "gemini" to "Gemini",
                    "ollama" to "Ollama",
                )
                val currentLabel = providers.find { it.first == settings.cloudProvider }?.second ?: "None"
                Box {
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { providerExpanded = true },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Default provider: $currentLabel", modifier = Modifier.weight(1f))
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(
                        expanded = providerExpanded,
                        onDismissRequest = { providerExpanded = false },
                    ) {
                        providers.forEach { (value, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    scope.launch { prefs.setCloudProvider(value) }
                                    providerExpanded = false
                                },
                            )
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                ApiKeyField("OpenAI API key", settings.openAIKey, showOpenAIKey,
                    onToggle = { showOpenAIKey = !showOpenAIKey },
                    onSave   = { scope.launch { prefs.setOpenAIKey(it) } })
                ApiKeyField("Anthropic API key", settings.anthropicKey, showAnthropicKey,
                    onToggle = { showAnthropicKey = !showAnthropicKey },
                    onSave   = { scope.launch { prefs.setAnthropicKey(it) } })
                ApiKeyField("Mistral API key", settings.mistralKey, showMistralKey,
                    onToggle = { showMistralKey = !showMistralKey },
                    onSave   = { scope.launch { prefs.setMistralKey(it) } })
                ApiKeyField("Gemini API key", settings.geminiKey, showGeminiKey,
                    onToggle = { showGeminiKey = !showGeminiKey },
                    onSave   = { scope.launch { prefs.setGeminiKey(it) } })
                var ollamaUrl by remember { mutableStateOf(settings.ollamaUrl) }
                OutlinedTextField(
                    value         = ollamaUrl,
                    onValueChange = { ollamaUrl = it },
                    label         = { Text("Ollama base URL") },
                    placeholder   = { Text("http://192.168.1.x:11434") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth(),
                    trailingIcon  = {
                        TextButton(onClick = { scope.launch { prefs.setOllamaUrl(ollamaUrl) } }) {
                            Text("Save")
                        }
                    },
                )
            }

            // ── Knowledge Base ────────────────────────────────────────────────
            SettingsSection("Knowledge Base (Vector DB)") {
                SettingsSlider("Chunk size (tokens)", settings.chunkSize.toFloat(), 128f, 1024f) {
                    scope.launch { prefs.setChunkSize(it.toInt()) }
                }
                SettingsSlider("Chunk overlap (%)", settings.chunkOverlapPct.toFloat(), 0f, 50f) {
                    scope.launch { prefs.setChunkOverlap(it.toInt()) }
                }
            }

            // ── Overlay ───────────────────────────────────────────────────────
            SettingsSection("Overlay") {
                SettingsRow(
                    icon  = Icons.Default.Layers,
                    label = "Manage overlay permission",
                    onClick = {
                        val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:${context.packageName}"))
                        context.startActivity(intent)
                    }
                )
            }
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(AgentSurface2, RoundedCornerShape(12.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(title, fontSize = 13.sp, color = AgentBlue)
        content()
    }
}

@Composable
private fun SettingsRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String = "", onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color   = AgentSurface2,
        shape   = RoundedCornerShape(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = AgentBlue)
            Spacer(Modifier.width(12.dp))
            Text(label, Modifier.weight(1f))
            if (value.isNotBlank()) Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(0.5f))
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurface.copy(0.4f))
        }
    }
}

@Composable
private fun ApiKeyField(label: String, current: String, visible: Boolean, onToggle: () -> Unit, onSave: (String) -> Unit) {
    var value by remember(current) { mutableStateOf(current) }
    OutlinedTextField(
        value         = value,
        onValueChange = { value = it },
        label         = { Text(label) },
        singleLine    = true,
        modifier      = Modifier.fillMaxWidth(),
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        trailingIcon  = {
            Row {
                IconButton(onClick = onToggle) {
                    Icon(if (visible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                }
                TextButton(onClick = { onSave(value) }) { Text("Save") }
            }
        },
    )
}

@Composable
private fun SettingsSlider(label: String, value: Float, min: Float, max: Float, onChanged: (Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp)
            Text(value.toInt().toString(), fontSize = 13.sp, color = AgentBlue)
        }
        Slider(value = value, onValueChange = onChanged, valueRange = min..max, colors = SliderDefaults.colors(thumbColor = AgentBlue, activeTrackColor = AgentBlue))
    }
}
