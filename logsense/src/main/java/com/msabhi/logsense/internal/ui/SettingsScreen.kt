package com.msabhi.logsense.internal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.msabhi.logsense.BuildConfig
import com.msabhi.logsense.R
import com.msabhi.logsense.ThemeMode
import com.msabhi.logsense.internal.LogSenseCore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(core: LogSenseCore, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val themeMode by core.themeMode.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(LogSenseIcons.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            ) {
                Text("Theme", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                val modes = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { i, (mode, label) ->
                        SegmentedButton(
                            selected = themeMode == mode,
                            onClick = { core.setThemeMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                        ) { Text(label) }
                    }
                }
                Text(
                    "Colors follow your wallpaper (Material You) on Android 12+.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            AboutFooter()
        }
    }
}

@Composable
private fun AboutFooter() {
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 32.dp, bottom = 20.dp)
            // No ripple/visual affordance — tapping anywhere on the block opens the repo.
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { openUrl(context, REPO_URL) },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(
            text = stringResource(R.string.logsense_name).uppercase(),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp),
            color = cs.onSurfaceVariant,
        )
        Text(
            text = BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
            color = cs.onSurface,
        )
        Box(
            Modifier
                .padding(top = 2.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(cs.primary.copy(alpha = 0.13f))
                .padding(horizontal = 11.dp, vertical = 5.dp),
        ) {
            Text(
                text = "APACHE 2.0",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace, letterSpacing = 1.4.sp),
                color = cs.primary,
            )
        }
    }
}

private const val REPO_URL = "https://github.com/abhimuktheeswarar/LogSense"

private fun openUrl(context: android.content.Context, url: String) {
    runCatching {
        context.startActivity(
            android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}
