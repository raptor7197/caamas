package com.main.agent

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.main.agent.agent.AgentCore
import com.main.agent.agent.AgentRouter
import com.main.agent.agent.ReActLoop
import com.main.agent.agent.SessionManager
import com.main.agent.llm.DeviceCapability
import com.main.agent.llm.LlamaEngine
import com.main.agent.llm.ModelManager
import com.main.agent.llm.cloud.AnthropicProvider
import com.main.agent.llm.cloud.MistralProvider
import com.main.agent.llm.cloud.OllamaProvider
import com.main.agent.llm.cloud.OpenAIProvider
import com.main.agent.preferences.UserPreferences
import com.main.agent.rag.EmbeddingEngine
import com.main.agent.rag.FileIndexer
import com.main.agent.rag.RAGRetriever
import com.main.agent.rag.VectorDbConfig
import com.main.agent.rag.VectorStore
import com.main.agent.tools.base.ToolRegistry
import com.main.agent.ui.*
import com.main.agent.ui.theme.AgentTheme
import com.main.agent.voice.TTSEngine
import com.main.agent.voice.VoicePipeline
import com.main.agent.voice.WhisperSTT
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    private val engine       = LlamaEngine()
    private var agentCore:   AgentCore? = null
    private var modelManager: ModelManager? = null
    private var voicePipeline: VoicePipeline? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        modelManager?.updateModelsDir()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        checkStoragePermission()

        val app   = application as AgentApp
        val prefs = app.prefs
        val cap   = DeviceCapability.detect(this)

        setContent {
            AgentTheme {
                val settings by prefs.settingsFlow.collectAsState(initial = UserPreferences.Settings())

                var screen by remember { mutableStateOf<Screen>(Screen.Splash) }

                LaunchedEffect(Unit) {
                    screen = if (!settings.onboardingDone) Screen.Onboarding else Screen.Loading
                }

                LaunchedEffect(screen) {
                    if (screen == Screen.Loading) {
                        if (needsStoragePermission()) {
                            screen = Screen.StoragePermission
                        } else {
                            loadModel(cap, settings, app)
                            screen = Screen.Chat
                        }
                    }
                }

                val chatVm: ChatViewModel = viewModel()
                LaunchedEffect(agentCore, voicePipeline) {
                    if (agentCore != null) {
                        chatVm.agentCore = agentCore
                        chatVm.loadOrCreateSession()
                    }
                    voicePipeline?.let { chatVm.setVoicePipeline(it) }
                }

                when (screen) {
                    Screen.Splash    -> LoadingScreen("Starting\u2026")
                    Screen.Onboarding -> OnboardingScreen {
                        lifecycleScope.launch { prefs.setOnboardingDone(true) }
                        screen = Screen.Loading
                    }
                    Screen.Loading   -> {
                        val mmState by modelManager?.state?.collectAsState() ?: mutableStateOf(ModelManager.State.Idle)
                        LoadingScreen(
                            text = when (mmState) {
                                is ModelManager.State.Downloading -> "Downloading model\u2026"
                                is ModelManager.State.Verifying   -> "Verifying checksum\u2026"
                                is ModelManager.State.Loading     -> "Loading into memory\u2026"
                                is ModelManager.State.Error       -> "Error: ${(mmState as ModelManager.State.Error).message}"
                                else                             -> "Initializing\u2026"
                            },
                            state = mmState
                        )
                    }
                    Screen.StoragePermission -> StoragePermissionScreen(
                        onGranted = { screen = Screen.Loading },
                    )
                    Screen.Chat      -> ChatScreen(
                        viewModel      = chatVm,
                        onOpenSettings = { screen = Screen.Settings },
                    )
                    Screen.Settings  -> SettingsScreen(prefs = prefs, onBack = { screen = Screen.Chat })
                }
            }
        }
    }

    private fun needsStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
        if (Environment.isExternalStorageManager()) return false
        // Check if we can write to the default models directory
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "caamas/models",
        )
        return !dir.canWrite()
    }

    private suspend fun loadModel(
        cap:      DeviceCapability.Info,
        settings: UserPreferences.Settings,
        app:      AgentApp,
    ) {
        val mm = ModelManager(this, engine, cap)
        modelManager = mm
        mm.ensureReady()

        val cloud = when (settings.cloudProvider) {
            "openai"    -> if (settings.openAIKey.isNotBlank())    OpenAIProvider(settings.openAIKey)    else null
            "anthropic" -> if (settings.anthropicKey.isNotBlank()) AnthropicProvider(settings.anthropicKey) else null
            "mistral"   -> if (settings.mistralKey.isNotBlank())   MistralProvider(settings.mistralKey)  else null
            "ollama"    -> if (settings.ollamaUrl.isNotBlank())    OllamaProvider(settings.ollamaUrl)    else null
            else        -> null
        }

        val agentFolderUri = settings.agentFolderUri.takeIf { it.isNotBlank() }

        var ragRetriever: RAGRetriever? = null
        if (agentFolderUri != null) {
            val embedEngine = EmbeddingEngine()
            val embedModelFile = mm.modelsDir.resolve(ModelManager.ModelSpec.NOMIC_EMBED_TEXT.filename)
            if (embedModelFile.exists()) {
                embedEngine.loadModel(embedModelFile.absolutePath)
                val vecConfig  = VectorDbConfig()
                val vecStore   = VectorStore(this, vecConfig)
                vecStore.load()
                ragRetriever = RAGRetriever(vecStore, embedEngine, vecConfig)
                lifecycleScope.launch {
                    val indexer = FileIndexer(this@MainActivity, agentFolderUri, vecStore, embedEngine, vecConfig)
                    indexer.indexAll()
                }
            }
        }

        val registry = ToolRegistry(agentFolderUri, ragRetriever)
        val router   = AgentRouter(cloud)
        val loop     = ReActLoop(engine)
        val sessions = SessionManager(app.db)

        agentCore = AgentCore(this, engine, cap, registry, router, loop)

        val whisperFile = mm.modelsDir.resolve(ModelManager.ModelSpec.WHISPER_BASE_EN.filename)
        if (whisperFile.exists()) {
            val whisperSTT = WhisperSTT(this)
            whisperSTT.loadModel(whisperFile.absolutePath)
            val tts = TTSEngine(this)
            voicePipeline = VoicePipeline(this, whisperSTT, tts, agentCore!!, lifecycleScope)
        }
    }

    private fun checkStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:$packageName")
                    requestPermissionLauncher.launch(intent)
                    Toast.makeText(this, "Please allow All Files Access to keep models after uninstall", Toast.LENGTH_LONG).show()
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    requestPermissionLauncher.launch(intent)
                }
            }
        }
    }

    override fun onDestroy() {
        engine.unload()
        voicePipeline?.let { vp ->
            vp.cancel()
        }
        super.onDestroy()
    }

    private enum class Screen { Splash, Onboarding, Loading, StoragePermission, Chat, Settings }
}

@Composable
private fun LoadingScreen(text: String, state: ModelManager.State = ModelManager.State.Idle) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (state is ModelManager.State.Downloading) {
                Text(text, style = MaterialTheme.typography.titleMedium)
                LinearProgressIndicator(
                    progress = state.progress,
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
                val percent = (state.progress * 100).toInt()
                val downloadedMb = (state.progress * state.bytesTotal) / (1024 * 1024)
                val totalMb = state.bytesTotal / (1024 * 1024)
                Text(
                    text = "$percent% ($downloadedMb MB / $totalMb MB)",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                CircularProgressIndicator()
                Text(text)
            }
        }
    }
}

@Composable
private fun StoragePermissionScreen(onGranted: () -> Unit) {
    val ctx = LocalContext.current
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Storage permission required")
            Text(
                text = "Models are stored in Downloads/caamas/ to survive app reinstalls. " +
                       "Enable 'Allow access to manage all files' in system settings.",
                modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${ctx.packageName}")
                }
                ctx.startActivity(intent)
            }) {
                Text("Open Settings")
            }
            Button(onClick = onGranted) {
                Text("I've enabled it — continue")
            }
        }
    }
}
