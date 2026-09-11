package com.enajid.apkbuilder.ui.agent

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enajid.apkbuilder.data.ai.AiDebugLog
import com.enajid.apkbuilder.data.ai.ModelProfile
import com.enajid.apkbuilder.data.ai.ModelRole
import com.enajid.apkbuilder.data.ai.ProviderPresets
import com.enajid.apkbuilder.data.ai.Skill
import com.enajid.apkbuilder.ui.editor.EditorViewModel
import com.enajid.apkbuilder.ui.editor.EditorViewModel.AgentBubble
import com.enajid.apkbuilder.util.Intents
import kotlinx.coroutines.delay

/**
 * The AI Agent chat: a bottom sheet over the editor. The user brings their
 * own AI providers (OpenRouter, Groq, Gemini, Cerebras or any custom
 * OpenAI-compatible URL) — keys live only on the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSheet(
    viewModel: EditorViewModel,
    selectionInfo: () -> Pair<String?, String?>,
) {
    val state by viewModel.agentState.collectAsStateWithLifecycle()
    if (!state.open) return

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp

    var input by remember { mutableStateOf("") }
    var showSetup by remember { mutableStateOf(!state.hasCoder) }
    var showDebug by remember { mutableStateOf(false) }
    var editProfile by remember { mutableStateOf<ModelProfile?>(null) }
    var addProfileDialog by remember { mutableStateOf(false) }
    var deleteProfileTarget by remember { mutableStateOf<ModelProfile?>(null) }
    var skillDialog by remember { mutableStateOf(false) }
    var deleteSkillTarget by remember { mutableStateOf<Skill?>(null) }

    LaunchedEffect(state.pendingInput) {
        state.pendingInput?.let {
            input = it
            viewModel.consumePendingInput()
        }
    }
    LaunchedEffect(state.hasCoder) {
        if (!state.hasCoder) showSetup = true
    }
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(5000)
            viewModel.consumeAgentNotice()
        }
    }

    ModalBottomSheet(
        onDismissRequest = { viewModel.closeAgent() },
        sheetState = sheetState,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .height(sheetHeight)
                .imePadding()
                .padding(horizontal = 16.dp)
        ) {
            // ---- header ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.SmartToy,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("AI Agent", style = MaterialTheme.typography.titleMedium)
                    ModelsSummaryLine(state)
                }
                IconButton(onClick = { showDebug = !showDebug }) {
                    Icon(
                        Icons.Rounded.BugReport,
                        contentDescription = "AI debug log",
                        tint = if (showDebug) MaterialTheme.colorScheme.primary else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                IconButton(onClick = { showSetup = !showSetup }) {
                    Icon(Icons.Rounded.Settings, contentDescription = "AI settings")
                }
            }

            state.notice?.let { notice ->
                Text(
                    notice,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }

            when {
                showDebug -> DebugContent(
                    state = state,
                    modifier = Modifier.weight(1f),
                    onTest = { viewModel.testCoderConnection() },
                    onClear = { viewModel.clearDebug() },
                    onRefresh = { viewModel.refreshDebug() },
                )
                showSetup -> AgentSetupContent(
                    state = state,
                    modifier = Modifier.weight(1f),
                    onAddProfile = { addProfileDialog = true },
                    onEditProfile = { editProfile = it },
                    onDeleteProfile = { deleteProfileTarget = it },
                    onToggleProfile = { id, enabled -> viewModel.setProfileEnabled(id, enabled) },
                    onSetRole = { id, role -> viewModel.setRole(id, role) },
                    onAddSkill = { skillDialog = true },
                    onToggleSkill = { viewModel.toggleSkill(it) },
                    onDeleteSkill = { deleteSkillTarget = it },
                )
                else -> {
                // ---- messages ----
                val listState = rememberLazyListState()
                LaunchedEffect(state.messages.size, state.busy) {
                    if (state.messages.isNotEmpty()) {
                        listState.animateScrollToItem(state.messages.lastIndex)
                    }
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(state.messages, key = { it.id }) { bubble ->
                        AgentBubbleRow(bubble)
                    }
                    if (state.busy) {
                        item {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Agent কাজ করছে…",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                // ---- input ----
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom,
                ) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("কী বানাতে চাও? যেমন: একটা টিপ ক্যালকুলেটর") },
                        maxLines = 4,
                        enabled = !state.busy,
                    )
                    Spacer(Modifier.width(8.dp))
                    if (state.busy) {
                        IconButton(onClick = { viewModel.stopAgent() }) {
                            Icon(Icons.Rounded.Stop, contentDescription = "Stop")
                        }
                    } else {
                        FilledIconButton(
                            onClick = {
                                val (path, selected) = selectionInfo()
                                viewModel.sendAgentMessage(input, path, selected)
                                input = ""
                            },
                            enabled = input.isNotBlank() && state.hasCoder,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
                        }
                    }
                }
                }
            }
        }
    }

    if (addProfileDialog || editProfile != null) {
        ProfileDialog(
            state = state,
            initial = editProfile,
            onDismiss = {
                addProfileDialog = false
                editProfile = null
                viewModel.clearLoadedModels()
            },
            onSave = { providerId, baseUrl, apiKey, model, role ->
                val editing = editProfile
                if (editing == null) {
                    viewModel.addProfile(providerId, baseUrl, apiKey, model, role)
                } else {
                    viewModel.updateProfile(
                        editing.copy(
                            providerId = providerId,
                            baseUrl = baseUrl,
                            apiKey = apiKey.ifBlank { editing.apiKey },
                            model = model,
                            role = role,
                        )
                    )
                }
                addProfileDialog = false
                editProfile = null
                viewModel.clearLoadedModels()
            },
            onLoadModels = { baseUrl, apiKey -> viewModel.loadModels(baseUrl, apiKey) },
        )
    }

    if (skillDialog) {
        SkillDialog(
            onDismiss = { skillDialog = false },
            onSave = { name, instructions ->
                viewModel.addSkill(name, instructions)
                skillDialog = false
            },
        )
    }

    deleteProfileTarget?.let { profile ->
        AlertDialog(
            onDismissRequest = { deleteProfileTarget = null },
            title = { Text("Model বাদ দেবে?") },
            text = { Text("${profile.summary} মুছে ফেলা হবে। আবার যোগ করতে চাইলে key আবার বসাতে হবে।") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProfile(profile.id)
                    deleteProfileTarget = null
                }) { Text("বাদ দাও") }
            },
            dismissButton = {
                TextButton(onClick = { deleteProfileTarget = null }) { Text("না") }
            },
        )
    }

    deleteSkillTarget?.let { skill ->
        AlertDialog(
            onDismissRequest = { deleteSkillTarget = null },
            title = { Text("Skill বাদ দেবে?") },
            text = { Text("\"${skill.name}\" মুছে ফেলা হবে।") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteSkill(skill.id)
                    deleteSkillTarget = null
                }) { Text("বাদ দাও") }
            },
            dismissButton = {
                TextButton(onClick = { deleteSkillTarget = null }) { Text("না") }
            },
        )
    }
}

@Composable
private fun ModelsSummaryLine(state: EditorViewModel.AgentUiState) {
    val enabled = state.profiles.filter { it.enabled }
    val text = when {
        enabled.isEmpty() -> "কোনো model নেই — ⚙ setup"
        else -> buildString {
            enabled.firstOrNull { it.role == ModelRole.CODER }?.let { append("Coder: ${it.model}") }
            enabled.firstOrNull { it.role == ModelRole.REVIEWER }?.let {
                if (isNotEmpty()) append(" · ")
                append("Reviewer: ${it.model}")
            }
            enabled.count { it.role == ModelRole.FALLBACK }.let {
                if (it > 0) {
                    if (isNotEmpty()) append(" · ")
                    append("$it fallback")
                }
            }
        }
    }
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun AgentBubbleRow(bubble: AgentBubble) {
    when (bubble.kind) {
        AgentBubble.Kind.TEXT -> Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = if (bubble.fromUser) Arrangement.End else Arrangement.Start,
        ) {
            Surface(
                color = if (bubble.fromUser) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.widthIn(max = 300.dp),
            ) {
                SelectionContainer {
                    Text(
                        bubble.text,
                        Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        AgentBubble.Kind.TOOL -> Text(
            "⚙ ${bubble.text}",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        AgentBubble.Kind.ERROR -> Text(
            "⚠ ${bubble.text}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

// ------------------------------------------------------------------ setup --

@Composable
private fun AgentSetupContent(
    state: EditorViewModel.AgentUiState,
    modifier: Modifier = Modifier,
    onAddProfile: () -> Unit,
    onEditProfile: (ModelProfile) -> Unit,
    onDeleteProfile: (ModelProfile) -> Unit,
    onToggleProfile: (Long, Boolean) -> Unit,
    onSetRole: (Long, ModelRole) -> Unit,
    onAddSkill: () -> Unit,
    onToggleSkill: (Long) -> Unit,
    onDeleteSkill: (Skill) -> Unit,
) {
    LazyColumn(
        modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "নিজের AI provider যোগ করো (OpenRouter, Groq, Gemini, Cerebras…)। " +
                    "ফ্রি tier বা \":free\" model ব্যবহার করলে পুরোটাই $0। " +
                    "Coder মূল কোড লেখে, Reviewer একবার দেখে ভুল ধরে, Fallback হলো ব্যাকআপ।",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Text("Models", style = MaterialTheme.typography.titleSmall)
        }

        if (state.profiles.isEmpty()) {
            item {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text(
                            "এখনো কোনো model নেই",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "একটা Coder model যোগ করলেই agent চালু হবে।",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        } else {
            items(state.profiles, key = { "profile-${it.id}" }) { profile ->
                ProfileRow(
                    profile = profile,
                    test = state.profileTests[profile.id],
                    testing = state.testingProfileId == profile.id,
                    onEdit = { onEditProfile(profile) },
                    onDelete = { onDeleteProfile(profile) },
                    onToggle = { onToggleProfile(profile.id, it) },
                    onSetRole = { onSetRole(profile.id, it) },
                )
            }
        }

        item {
            FilledTonalButton(onClick = onAddProfile, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("নতুন model যোগ করো")
            }
        }

        item {
            Text("Skills", style = MaterialTheme.typography.titleSmall)
            Text(
                "চালু skill-গুলোর নির্দেশনা agent-এর প্রতি কাজে system prompt-এ যোগ হয়।",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        items(state.skills, key = { "skill-${it.id}" }) { skill ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(skill.name, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        skill.instructions,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!skill.builtIn) {
                    IconButton(onClick = { onDeleteSkill(skill) }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            Icons.Rounded.Delete,
                            contentDescription = "Delete skill",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = skill.enabled,
                    onCheckedChange = { onToggleSkill(skill.id) },
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }

        item {
            OutlinedButton(onClick = onAddSkill) {
                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("নিজের skill লিখো")
            }
        }
    }
}

@Composable
private fun ProfileRow(
    profile: ModelProfile,
    test: EditorViewModel.ProfileTest?,
    testing: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onSetRole: (ModelRole) -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        profile.model,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        profile.providerLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = "Edit",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "Delete",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = profile.enabled, onCheckedChange = onToggle)
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ModelRole.entries.forEach { role ->
                    FilterChip(
                        selected = profile.role == role,
                        onClick = { onSetRole(role) },
                        label = {
                            Text(
                                when (role) {
                                    ModelRole.CODER -> "Coder"
                                    ModelRole.REVIEWER -> "Reviewer"
                                    ModelRole.FALLBACK -> "Fallback"
                                },
                                style = MaterialTheme.typography.labelSmall,
                            )
                        },
                    )
                }
            }

            when {
                testing -> Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "টেস্ট চলছে…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                test != null -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (test.ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = if (test.ok) {
                            MaterialTheme.colorScheme.tertiary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        test.message,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (test.ok) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- dialogs --

@Composable
private fun ProfileDialog(
    state: EditorViewModel.AgentUiState,
    initial: ModelProfile?,
    onDismiss: () -> Unit,
    onSave: (providerId: String, baseUrl: String, apiKey: String, model: String, role: ModelRole) -> Unit,
    onLoadModels: (baseUrl: String, apiKey: String) -> Unit,
) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var providerId by remember { mutableStateOf(initial?.providerId ?: ProviderPresets.OPENROUTER.id) }
    var baseUrl by remember { mutableStateOf(initial?.baseUrl ?: ProviderPresets.OPENROUTER.baseUrl) }
    var apiKey by remember { mutableStateOf(initial?.apiKey ?: "") }
    var model by remember { mutableStateOf(initial?.model ?: "") }
    var role by remember { mutableStateOf(initial?.role ?: ModelRole.CODER) }

    val preset = ProviderPresets.byId(providerId)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "নতুন model" else "Model edit করো") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // provider chips
                ProviderPresets.all.forEach { p ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                providerId = p.id
                                baseUrl = p.baseUrl
                            }
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = providerId == p.id,
                            onCheckedChange = {
                                providerId = p.id
                                baseUrl = p.baseUrl
                            },
                        )
                        Column {
                            Text(p.label, style = MaterialTheme.typography.bodyMedium)
                            p.freeHint?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("API key") },
                    placeholder = { Text("sk-… / gsk_…") },
                    singleLine = true,
                    trailingIcon = {
                        IconButton(onClick = {
                            apiKey = clipboard.getText()?.text?.trim().orEmpty().ifBlank { apiKey }
                        }) {
                            Icon(
                                Icons.Rounded.ContentCopy,
                                contentDescription = "Clipboard থেকে paste",
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                    supportingText = {
                        preset.keyUrl?.let { url ->
                            TextButton(onClick = { Intents.openUrl(context, url) }) {
                                Text("Key নাও (${preset.label} dashboard)")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model ID") },
                    placeholder = { Text("যেমন: deepseek/deepseek-chat-v3.1:free") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                OutlinedButton(
                    onClick = { onLoadModels(baseUrl, apiKey) },
                    enabled = baseUrl.isNotBlank() && apiKey.isNotBlank() && !state.modelsLoading,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (state.modelsLoading) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Models লোড করো")
                    }
                }

                if (state.models.isNotEmpty()) {
                    Text(
                        "ট্যাপ করে model বেছে নাও:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.models.filter { it.contains("free", ignoreCase = true) }
                        .take(10)
                        .forEach { id ->
                            Text(
                                id,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { model = id }
                                    .padding(vertical = 2.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    if (state.models.none { it.contains("free", ignoreCase = true) }) {
                        state.models.take(8).forEach { id ->
                            Text(
                                id,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { model = id }
                                    .padding(vertical = 2.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }

                Text(
                    "এই model-এর কাজ:",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ModelRole.entries.forEach { r ->
                        FilterChip(
                            selected = role == r,
                            onClick = { role = r },
                            label = {
                                Text(
                                    when (r) {
                                        ModelRole.CODER -> "Coder"
                                        ModelRole.REVIEWER -> "Reviewer"
                                        ModelRole.FALLBACK -> "Fallback"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            },
                        )
                    }
                }
                Text(
                    when (role) {
                        ModelRole.CODER -> "মূল কোড লেখে (একটাই Coder থাকতে পারে)"
                        ModelRole.REVIEWER -> "Coder-এর কাজ একবার দেখে ভুল ধরে"
                        ModelRole.FALLBACK -> "আগের model fail করলে এটা এগিয়ে যায়"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(providerId, baseUrl, apiKey, model, role) },
                enabled = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank(),
            ) { Text("সেভ করে টেস্ট করো") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("না") }
        },
    )
}

@Composable
private fun SkillDialog(
    onDismiss: () -> Unit,
    onSave: (name: String, instructions: String) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var instructions by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("নিজের skill") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "যেমন — নাম: \"Material You\", নির্দেশনা: \"সব screen-এ dynamic color ব্যবহার করবি\"",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Skill-এর নাম") },
                    singleLine = true,
                )
                OutlinedTextField(
                    value = instructions,
                    onValueChange = { instructions = it },
                    label = { Text("নির্দেশনা (agent প্রতিবার পাবে)") },
                    minLines = 3,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, instructions) },
                enabled = name.isNotBlank() && instructions.isNotBlank(),
            ) { Text("যোগ করো") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("না") }
        },
    )
}

// ------------------------------------------------------------------ debug --

/**
 * The 🐞 pane: every AI HTTP exchange, agent step, fallback and error, with
 * a one-tap end-to-end connection test. Built so a user can copy the log and
 * paste it into a bug report.
 */
@Composable
private fun DebugContent(
    state: EditorViewModel.AgentUiState,
    modifier: Modifier = Modifier,
    onTest: () -> Unit,
    onClear: () -> Unit,
    onRefresh: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(Unit) { onRefresh() }

    Column(modifier.fillMaxWidth()) {
        Text(
            "প্রতিটা AI request/response, agent step আর error এখানে লগ হয় " +
                "(API key কখনো পুরোটা দেখানো হয় না)। সমস্যা হলে এই লগ copy করে রিপোর্ট করো।",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(
                onClick = onTest,
                enabled = !state.testRunning && state.profiles.any { it.enabled },
                modifier = Modifier.weight(1f),
            ) {
                if (state.testRunning) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text("টেস্ট")
            }
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(AiDebugLog.shareText()))
                },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Rounded.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text("Copy")
            }
            OutlinedButton(onClick = onClear) {
                Text("Clear")
            }
        }
        Spacer(Modifier.height(8.dp))

        val entries = state.debugEntries
        if (entries.isEmpty()) {
            Text(
                "কোনো লগ নেই — টেস্ট চাপো বা agent-কে কিছু কাজ দাও।",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            LazyColumn(
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(entries.asReversed(), key = { i, e -> "${e.timeMs}-$i-${e.message.hashCode()}" }) { _, entry ->
                    Column {
                        Text(
                            "[${AiDebugLog.formatTime(entry.timeMs)}] ${entry.level}/${entry.event}",
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = when (entry.level) {
                                AiDebugLog.Level.ERROR, AiDebugLog.Level.FATAL ->
                                    MaterialTheme.colorScheme.error
                                AiDebugLog.Level.OK -> MaterialTheme.colorScheme.tertiary
                                AiDebugLog.Level.WARN -> MaterialTheme.colorScheme.secondary
                                AiDebugLog.Level.INFO -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        Text(
                            entry.message,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        entry.details?.let { details ->
                            Text(
                                details.take(700),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
