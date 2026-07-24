package com.msabhi.lsapp

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.msabhi.logsense.LogSense
import com.msabhi.lsapp.ui.theme.LsappTheme

private const val TAG = "LsApp"

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            LsappTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    DemoScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun DemoScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = { context.startActivity(LogSense.getLaunchIntent(context)) },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Open LogSense") }

        SectionTitle("Logs")
        DemoButton("Log one of each level") {
            Log.v(TAG, "Verbose: entering the Matrix…")
            Log.d(TAG, "Debug: it works on my machine")
            Log.i(TAG, "Info: shipped to prod on a Friday")
            Log.w(TAG, "Warning: here be dragons")
            Log.e(TAG, "Error: task failed successfully")
            Log.wtf(TAG, "WTF: 0.1 + 0.2 = 0.30000000000000004")
        }
        DemoButton("Log exception with stacktrace") {
            Log.e(TAG, "Rescued a panic while herding goroutines", RuntimeException("stack overflow at ${'$'}throw"))
        }
        DemoButton("Spam 500 logs") {
            repeat(500) { Log.d(TAG, "Spam log line #$it with some payload data") }
        }
        DemoButton("Multi-line log") {
            Log.i(TAG, "First line\nsecond line of the same message\nthird line")
        }

        SectionTitle("Analytics (tag: Analytics)")
        DemoButton("JSON event") {
            Log.d("Analytics", """achievement_unlocked {"name":"1000_commits","rarity":"legendary","xp":9001}""")
        }
        DemoButton("key=value event") {
            Log.d("Analytics", "coffee_brewed roast=dark, shots=2, blocker_resolved=true")
        }
        DemoButton("Bundle event") {
            Log.d("Analytics", "boss_defeated Bundle[{boss=merge_conflict, weapon=git_rebase, attempts=42}]")
        }

        SectionTitle("Crashes")
        DemoButton("JVM crash") {
            throw IllegalStateException(
                "Heisenbug: it only crashes when nobody is watching",
                RuntimeException("the printer is on fire, and 0.1 + 0.2 != 0.3"),
            )
        }
        DemoButton("ANR (tap repeatedly during freeze)") {
            Thread.sleep(20_000)
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp))
}

@Composable
private fun DemoButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(label) }
}
