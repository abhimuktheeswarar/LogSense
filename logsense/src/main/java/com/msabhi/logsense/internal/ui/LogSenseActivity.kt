package com.msabhi.logsense.internal.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.msabhi.logsense.ThemeMode
import com.msabhi.logsense.internal.LogSenseCore
import com.msabhi.logsense.internal.ui.theme.LogSenseTheme

internal class LogSenseActivity : ComponentActivity() {

    private var pendingCrashId by mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingCrashId = intent.crashId
        setContent {
            val core = LogSenseCore.instance
            if (core == null) {
                LogSenseTheme(ThemeMode.SYSTEM, accentColor = null) { NotInitializedScreen() }
            } else {
                LogSenseApp(
                    core = core,
                    pendingCrashId = pendingCrashId,
                    onCrashIdConsumed = { pendingCrashId = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingCrashId = intent.crashId
    }

    private val Intent.crashId: Long?
        get() = getLongExtra(EXTRA_CRASH_ID, -1L).takeIf { it >= 0 }

    companion object {
        const val EXTRA_CRASH_ID = "com.msabhi.logsense.EXTRA_CRASH_ID"
    }
}

@Composable
private fun NotInitializedScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = "LogSense is not initialized.\n\n" +
                "Call LogSense.init(this) from your Application.onCreate().",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(32.dp),
        )
    }
}
