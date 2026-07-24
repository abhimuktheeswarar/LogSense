package com.msabhi.logsense.internal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.msabhi.logsense.BuildConfig
import com.msabhi.logsense.R
import com.msabhi.logsense.ThemeMode
import com.msabhi.logsense.internal.LogSenseCore
import com.msabhi.logsense.internal.reader.LogLevel
import com.msabhi.logsense.internal.ui.theme.color

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(core: LogSenseCore, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val themeMode by core.themeMode.collectAsState()
    val cs = MaterialTheme.colorScheme

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
                // ---------- Storage ----------
                Text("Storage", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                val keepEvents by core.prefs.keepPastEvents.collectAsState()
                val keepCrashes by core.prefs.keepPastCrashes.collectAsState()
                SettingIconRow(
                    icon = LogSenseIcons.Database,
                    iconTint = LogLevel.INFO.color(),
                    title = "Keep previous-session events",
                    subtitle = "Show analytics events from earlier app runs, not just this one.",
                    onClick = { core.setKeepPastEvents(!keepEvents) },
                ) { Switch(checked = keepEvents, onCheckedChange = { core.setKeepPastEvents(it) }) }
                HorizontalDivider(color = cs.outlineVariant)
                SettingIconRow(
                    icon = LogSenseIcons.Warning,
                    iconTint = LogLevel.ERROR.color(),
                    title = "Keep previous-session crashes",
                    subtitle = "Show crashes from earlier app runs, not just this one.",
                    onClick = { core.setKeepPastCrashes(!keepCrashes) },
                ) { Switch(checked = keepCrashes, onCheckedChange = { core.setKeepPastCrashes(it) }) }
                HorizontalDivider(color = cs.outlineVariant)
                SettingIconRow(
                    icon = LogSenseIcons.Lines,
                    iconTint = LogLevel.DEBUG.color(),
                    title = "Log buffer limit",
                    subtitle = "Oldest lines drop once the buffer is full. Configured in code.",
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "%,d".format(core.config.maxBufferedLines),
                            style = MaterialTheme.typography.titleMedium.copy(fontFamily = FontFamily.Monospace),
                            color = cs.onSurface,
                        )
                        Text("lines", style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                }

                // ---------- Events ----------
                Spacer(Modifier.height(24.dp))
                Text("Events", style = MaterialTheme.typography.titleMedium)
                CaptureTagsEditor(core)

                Spacer(Modifier.height(22.dp))
                EventPatternEditor(core)

                // ---------- Theme ----------
                Spacer(Modifier.height(24.dp))
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
            }
            AboutFooter()
        }
    }
}

/** A settings row with a tinted leading glyph, title + subtitle, and a trailing control (switch/value). */
@Composable
private fun SettingIconRow(
    icon: ImageVector,
    iconTint: Color,
    title: String,
    subtitle: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 14.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(cs.surfaceContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(19.dp)) }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = cs.onSurface)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
        Spacer(Modifier.width(12.dp))
        trailing()
    }
}

/** Capture-tag chips: code-set tags are locked; user tags are removable; the dashed chip adds one. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CaptureTagsEditor(core: LogSenseCore) {
    val cs = MaterialTheme.colorScheme
    val configTags = remember { core.config.analyticsTags.toList() }
    val userTags = remember {
        mutableStateListOf<String>().apply {
            addAll(core.prefs.eventTags.value.lines().map { it.trim() }.filter { it.isNotEmpty() })
        }
    }
    var showAdd by remember { mutableStateOf(false) }
    fun persist() = core.prefs.setEventTags(userTags.joinToString("\n"))

    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text("Capture tags", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        Text(
            "${configTags.size + userTags.size} tags",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = cs.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(10.dp))
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(9.dp),
        verticalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        configTags.forEach { tag -> LockedTagChip(tag) }
        userTags.forEach { tag -> key(tag) { TagChip(tag) { userTags.remove(tag); persist() } } }
        AddTagChip { showAdd = true }
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(LogSenseIcons.Lock, contentDescription = null, tint = cs.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            "Tags set in code can't be edited here. All are captured as analytics events.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }

    if (showAdd) {
        AddTagDialog(
            existing = configTags + userTags,
            onDismiss = { showAdd = false },
            onAdd = { userTags.add(it); persist() },
        )
    }
}

@Composable
private fun EventPatternEditor(core: LogSenseCore) {
    val cs = MaterialTheme.colorScheme
    val saved by core.prefs.eventPattern.collectAsState()
    var editing by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(saved) }
    // Validate each non-empty line: must compile, and must declare the (?<name>…) group the
    // extractor needs — otherwise it's a valid regex that silently captures nothing ("random text").
    val error = remember(draft) {
        draft.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }
            .firstNotNullOfOrNull { line ->
                when {
                    runCatching { Regex(line) }.isFailure -> "Not a valid regular expression."
                    !line.contains("(?<name>") -> "Each pattern needs a (?<name>…) group to name the event."
                    else -> null
                }
            }
    }
    val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Custom event pattern", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        if (editing) {
            Text(
                "optional",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = cs.onSurfaceVariant,
            )
        } else {
            TextButton(
                onClick = { draft = saved; editing = true },
                contentPadding = PaddingValues(horizontal = 10.dp),
            ) {
                Icon(LogSenseIcons.Edit, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(5.dp))
                Text("Edit")
            }
        }
    }
    Spacer(Modifier.height(8.dp))

    if (editing) {
        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            label = { Text("Regex") },
            placeholder = { Text("(?<name>\\w+)\\s*->\\s*\\{(?<params>.*)\\}", style = mono) },
            textStyle = mono,
            isError = error != null,
            supportingText = if (error != null) {
                { Text(error) }
            } else {
                null
            },
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(
                onClick = { draft = "" },
                enabled = draft.isNotEmpty(),
                colors = ButtonDefaults.textButtonColors(contentColor = LogLevel.ERROR.color()),
            ) {
                Icon(LogSenseIcons.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Clear")
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { draft = saved; editing = false }) { Text("Cancel") }
            Spacer(Modifier.width(4.dp))
            Button(
                onClick = { core.prefs.setEventPattern(draft.trim()); editing = false },
                enabled = error == null && draft.trim() != saved, // only when there's an actual change to save
            ) {
                Icon(LogSenseIcons.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save")
            }
        }
    } else {
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, cs.outlineVariant, RoundedCornerShape(8.dp))
                .padding(14.dp),
        ) {
            Text(
                if (saved.isBlank()) "No pattern — using the built-in parser." else saved,
                style = mono,
                color = if (saved.isBlank()) cs.onSurfaceVariant else cs.onSurface,
            )
        }
    }
    Text(
        "One regex per line, tried in order. Named groups (?<name>…) and (?<params>…) pull the event " +
            "name and its params. Empty uses the built-in parser.",
        style = MaterialTheme.typography.bodySmall,
        color = cs.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun TagChip(tag: String, onRemove: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cs.surfaceContainer)
            .border(1.dp, cs.outlineVariant, RoundedCornerShape(10.dp))
            .padding(start = 14.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tag, style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace), color = cs.onSurface)
        Spacer(Modifier.width(6.dp))
        Box(
            Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onRemove).padding(3.dp),
            contentAlignment = Alignment.Center,
        ) { Icon(LogSenseIcons.Close, contentDescription = "Remove tag", tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp)) }
    }
}

@Composable
private fun LockedTagChip(tag: String) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(cs.onSurface.copy(alpha = 0.05f))
            .dashedBorder(cs.outline, 10.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(tag, style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace), color = cs.onSurfaceVariant)
        Spacer(Modifier.width(7.dp))
        Icon(LogSenseIcons.Lock, contentDescription = "Set in code", tint = cs.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun AddTagChip(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .dashedBorder(cs.primary, 10.dp)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LogSenseIcons.Add, contentDescription = null, tint = cs.primary, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(6.dp))
        Text("Add tag", style = MaterialTheme.typography.labelLarge, color = cs.primary)
    }
}

@Composable
private fun AddTagDialog(existing: List<String>, onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var input by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add capture tag") },
        text = {
            Column {
                Text(
                    "The logcat tag to capture as analytics events.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    label = { Text("Tag") },
                    placeholder = { Text("AnalyticsEngine", fontFamily = FontFamily.Monospace) },
                    textStyle = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val t = input.trim()
                    if (t.isNotEmpty() && t !in existing) onAdd(t)
                    onDismiss()
                },
                enabled = input.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A rounded dashed border, for the "Add tag" and locked (code-set) chips. */
private fun Modifier.dashedBorder(color: Color, radius: Dp, width: Dp = 1.dp): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius.toPx()),
        style = Stroke(width = width.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(9f, 6f))),
    )
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
            text = stringResource(R.string.logsense_name),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = cs.onSurface,
        )
        Text(
            text = BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
            color = cs.onSurfaceVariant,
        )
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
