package com.msabhi.logsense.internal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
                    subtitle = "Oldest lines drop once the buffer is full. Auto-reduced on low-RAM devices.",
                ) {
                    Column(horizontalAlignment = Alignment.End) {
                        Text(
                            "%,d".format(core.bufferLimit),
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

                // Footer scrolls with the content (no longer pinned) now that Settings has grown.
                AboutFooter()
            }
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

/**
 * Capture-tag rows: each tag carries an optional regex (blank = built-in parser). Tags set in code
 * are locked; QA can add new tags (with their own optional regex) and edit/remove those.
 */
@Composable
private fun CaptureTagsEditor(core: LogSenseCore) {
    val cs = MaterialTheme.colorScheme
    val configEntries = remember { core.config.analyticsTagPatterns.toList().sortedBy { it.first } }
    val settings by core.prefs.tagPatterns.collectAsState()
    val settingsEntries = remember(settings) { settings.toList().sortedBy { it.first } }
    var dialog by remember { mutableStateOf<TagPatternDraft?>(null) }
    var pendingRemove by remember { mutableStateOf<String?>(null) }

    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.Bottom) {
        Text("Capture tags", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        Text(
            "${configEntries.size + settingsEntries.size} tags",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
            color = cs.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        configEntries.forEach { (tag, pattern) -> TagPatternRow(tag, pattern, locked = true) }
        settingsEntries.forEach { (tag, pattern) ->
            key(tag) {
                TagPatternRow(
                    tag = tag,
                    pattern = pattern,
                    locked = false,
                    onEdit = { dialog = TagPatternDraft(tag, pattern.orEmpty(), isNew = false) },
                    onRemove = { pendingRemove = tag },
                )
            }
        }
        AddTagRow { dialog = TagPatternDraft("", "", isNew = true) }
    }
    Spacer(Modifier.height(12.dp))
    Row(verticalAlignment = Alignment.Top) {
        Icon(LogSenseIcons.Lock, contentDescription = null, tint = cs.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(13.dp).padding(top = 2.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            "Tags set in code are locked. Each tag's optional regex — named groups (?<name>…) and " +
                "(?<params>…) — extracts the event; without one the built-in parser is used.",
            style = MaterialTheme.typography.bodySmall,
            color = cs.onSurfaceVariant,
        )
    }

    dialog?.let { draft ->
        val existing = (configEntries.map { it.first } + settingsEntries.map { it.first })
            .let { if (draft.isNew) it else it - draft.tag }
        TagPatternDialog(
            draft = draft,
            existing = existing,
            onDismiss = { dialog = null },
            onSave = { tag, regex ->
                core.prefs.setTagPatterns(settings + (tag to regex.ifBlank { null }))
                dialog = null
            },
        )
    }

    pendingRemove?.let { tag ->
        AlertDialog(
            onDismissRequest = { pendingRemove = null },
            title = { Text("Remove \"$tag\"?") },
            text = { Text("Stop capturing this tag as analytics events. Events already captured are kept.") },
            confirmButton = {
                TextButton(
                    onClick = { core.prefs.setTagPatterns(settings - tag); pendingRemove = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = LogLevel.ERROR.color()),
                ) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { pendingRemove = null }) { Text("Cancel") } },
        )
    }
}

private data class TagPatternDraft(val tag: String, val pattern: String, val isNew: Boolean)

/** Non-null error for a tag's regex, or null if it's empty (optional) or valid. */
private fun patternError(regex: String): String? {
    val line = regex.trim()
    if (line.isEmpty()) return null
    return when {
        runCatching { Regex(line) }.isFailure -> "Not a valid regular expression."
        !line.contains("(?<name>") -> "Pattern needs a (?<name>…) group to name the event."
        else -> null
    }
}

/** One capture tag: name + a "regex"/"built-in parser" badge. Locked (config) rows show a lock;
 *  editable (Settings) rows are tappable to edit and carry a remove button. */
@Composable
private fun TagPatternRow(
    tag: String,
    pattern: String?,
    locked: Boolean,
    onEdit: () -> Unit = {},
    onRemove: () -> Unit = {},
) {
    val cs = MaterialTheme.colorScheme
    val hasRegex = !pattern.isNullOrBlank()
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
            .clip(RoundedCornerShape(10.dp))
            .then(if (locked) Modifier.background(cs.onSurface.copy(alpha = 0.05f)) else Modifier.background(cs.surfaceContainer))
            .then(if (locked) Modifier.dashedBorder(cs.outline, 10.dp) else Modifier.border(1.dp, cs.outlineVariant, RoundedCornerShape(10.dp)))
            .then(if (locked) Modifier else Modifier.clickable(onClick = onEdit))
            .padding(start = 14.dp, end = if (locked) 14.dp else 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                tag,
                style = MaterialTheme.typography.labelLarge.copy(fontFamily = FontFamily.Monospace),
                color = if (locked) cs.onSurfaceVariant else cs.onSurface,
            )
            Text(
                if (hasRegex) "regex" else "built-in parser",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                color = if (hasRegex) cs.primary else cs.onSurfaceVariant,
            )
        }
        if (locked) {
            Icon(LogSenseIcons.Lock, contentDescription = "Set in code", tint = cs.onSurfaceVariant.copy(alpha = 0.7f), modifier = Modifier.size(15.dp))
        } else {
            Box(
                Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onRemove).padding(6.dp),
                contentAlignment = Alignment.Center,
            ) { Icon(LogSenseIcons.Close, contentDescription = "Remove tag", tint = cs.onSurfaceVariant, modifier = Modifier.size(16.dp)) }
        }
    }
}

@Composable
private fun AddTagRow(onClick: () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 46.dp)
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

/** Add or edit a Settings tag: a tag name (locked when editing) + an optional, validated regex. */
@Composable
private fun TagPatternDialog(
    draft: TagPatternDraft,
    existing: List<String>,
    onDismiss: () -> Unit,
    onSave: (tag: String, regex: String) -> Unit,
) {
    val mono = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
    var tag by remember { mutableStateOf(draft.tag) }
    var regex by remember { mutableStateOf(draft.pattern) }
    val regexError = remember(regex) { patternError(regex) }
    val tagError = if (tag.isNotBlank() && tag.trim() in existing) "Tag already added." else null
    val canSave = tag.isNotBlank() && tagError == null && regexError == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.isNew) "Add capture tag" else "Edit ${draft.tag}") },
        text = {
            Column {
                OutlinedTextField(
                    value = tag,
                    onValueChange = { tag = it },
                    enabled = draft.isNew,
                    singleLine = true,
                    label = { Text("Tag") },
                    placeholder = { Text("e.g. AppEvents", style = mono) },
                    textStyle = mono,
                    isError = tagError != null,
                    supportingText = tagError?.let { { Text(it) } },
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = regex,
                    onValueChange = { regex = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    label = { Text("Regex (optional)") },
                    placeholder = { Text("(?<name>\\w+)\\s*->\\s*\\{(?<params>.*)\\}", style = mono) },
                    textStyle = mono,
                    isError = regexError != null,
                    supportingText = { Text(regexError ?: "Empty = built-in parser. Needs (?<name>…); (?<params>…) optional.") },
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(tag.trim(), regex.trim()) }, enabled = canSave) { Text("Save") }
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
