package com.main.agent.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.main.agent.AgentApp
import com.main.agent.ui.theme.AgentBlue
import kotlinx.coroutines.launch

@Composable
fun OnboardingScreen(onComplete: () -> Unit) {
    val context = LocalContext.current
    val scope   = rememberCoroutineScope()
    var step    by remember { mutableIntStateOf(0) }

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { step = maxOf(step, 1) }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            scope.launch {
                val app = context.applicationContext as AgentApp
                app.prefs.setAgentFolderUri(uri.toString())
            }
        }
        step = maxOf(step, 4)
    }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement   = Arrangement.Center,
        horizontalAlignment   = Alignment.CenterHorizontally,
    ) {
        Text("Welcome to Agent", fontSize = 26.sp, textAlign = TextAlign.Center)
        Text(
            "Private AI assistant running entirely on your device.",
            fontSize = 14.sp,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onBackground.copy(0.6f),
            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
        )

        OnboardingStep(
            complete = step > 0,
            icon     = Icons.Default.Mic,
            title    = "Microphone",
            desc     = "For voice commands",
        ) { micPermLauncher.launch(Manifest.permission.RECORD_AUDIO) }

        Spacer(Modifier.height(12.dp))

        OnboardingStep(
            complete = step > 1,
            icon     = Icons.Default.Layers,
            title    = "Floating Overlay",
            desc     = "Access the assistant from any screen",
        ) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"))
            context.startActivity(intent)
            step = maxOf(step, 2)
        }

        Spacer(Modifier.height(12.dp))

        OnboardingStep(
            complete = step > 2,
            icon     = Icons.Default.Folder,
            title    = "Agent Folder",
            desc     = "Choose where to save files",
        ) { folderPicker.launch(null) }

        Spacer(Modifier.height(32.dp))

        Button(
            onClick  = onComplete,
            enabled  = step >= 2,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            shape    = RoundedCornerShape(12.dp),
        ) {
            Text("Continue", fontSize = 16.sp)
        }
        if (step < 2) {
            Text("Grant Microphone and Overlay first",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onBackground.copy(0.5f),
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun OnboardingStep(
    complete: Boolean,
    icon:     androidx.compose.ui.graphics.vector.ImageVector,
    title:    String,
    desc:     String,
    onGrant:  () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (complete) AgentBlue else MaterialTheme.colorScheme.onSurface.copy(0.4f))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title)
                Text(desc, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(0.5f))
            }
            if (!complete) {
                TextButton(onClick = onGrant) { Text("Grant") }
            } else {
                Text("\u2713", color = AgentBlue)
            }
        }
    }
}
