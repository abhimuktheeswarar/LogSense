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
            Log.v(TAG, "Verbose: fine-grained diagnostic message")
            Log.d(TAG, "Debug: something happened worth debugging")
            Log.i(TAG, "Info: app flow milestone reached")
            Log.w(TAG, "Warning: something looks off")
            Log.e(TAG, "Error: something actually failed")
            Log.wtf(TAG, "WTF: this should never happen")
        }
        DemoButton("Log exception with stacktrace") {
            Log.e(TAG, "Caught exception while doing demo work", RuntimeException("Demo failure cause"))
        }
        DemoButton("Spam 500 logs") {
            repeat(500) { Log.d(TAG, "Spam log line #$it with some payload data") }
        }
        DemoButton("Multi-line log") {
            Log.i(TAG, "First line\nsecond line of the same message\nthird line")
        }

        SectionTitle("Analytics (tag: Analytics)")
        DemoButton("JSON event") {
            Log.d("Analytics", """purchase {"sku":"pro_upgrade","price":9.99,"currency":"USD"}""")
        }
        DemoButton("key=value event") {
            Log.d("Analytics", "screen_view screen=Home, source=bottom_tab, session_count=42")
        }
        DemoButton("Bundle event") {
            Log.d("Analytics", "add_to_cart Bundle[{item_id=42, item_name=Widget, quantity=2}]")
        }

        SectionTitle("Crashes")
        DemoButton("JVM crash") {
            throw RuntimeException("LogSense demo crash — everything is working as intended")
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
