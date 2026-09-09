package com.enajid.apkbuilder.ui.agent

import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.enajid.apkbuilder.data.ai.AiConfig
import com.enajid.apkbuilder.ui.editor.EditorViewModel
import com.enajid.apkbuilder.ui.editor.EditorViewModel.AgentBubble
import com.enajid.apkbuilder.util.Intents
import kotlinx.coroutines.delay

private const val TERMUX_RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND"
private const val F_DROID_TERMUX_URL = "https://f-droid.org/en/packages/com.termux/"
private const val OMNIROUTE_INSTALL_COMMAND =
    "pkg update && pkg upgrade -y && pkg install nodejs-lts git curl -y && npm install -g omniroute"
private const val OMNIROUTE_RUN_COMMAND = "omniroute"
private const val OMNIROUTE_DASHBOARD_URL = "http://localhost:20128"

/**
 * The AI Agent chat: a bottom sheet over the editor. Talks to the OmniRoute
 * gateway the user runs locally in Termux — nothing leaves the phone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSheet(
    viewModel: EditorViewModel,
    selectionInfo: () -> Pair<String?, String?>,
) {
    val state by viewModel.agentState.collectAsStateWithLifecycle()
    if (!state.open) return

    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sheetHeight = (LocalConfiguration.current.screenHeightDp * 0.85f).dp

    var input by remember { mutableStateOf("") }
    var showSetup by remember { mutableStateOf(!state.hasKey) }

    LaunchedEffect(state.pendingInput) {
        state.pendingInput?.let {
            input = it
            viewModel.consumePendingInput()
        }
    }
    LaunchedEffect(state.hasKey) {
        if (state.hasKey) showSetup = false
    }
    LaunchedEffect(state.notice) {
        if (state.notice != null) {
            delay(5000)
            viewModel.consumeAgentNotice()
        }
    }

    val runPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> viewModel.runOmniRoute(granted) }

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
                Text("AI Agent", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                Text(
                    text = when {
                        state.checking -> "checking…"
                        state.reachable == true -> "OmniRoute ✅"
                        state.reachable == false -> "OmniRoute ⛔"
                        else -> "OmniRoute"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (state.reachable == false) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
                IconButton(
                    onClick = { viewModel.refreshAgentStatus() },
                    enabled = !state.checking,
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh status")
                }
                IconButton(onClick = { showSetup = !showSetup }) {
                    Icon(Icons.Rounded.Settings, contentDescription = "AI settings")
                }
            }

            if (state.reachable == false && !showSetup) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "OmniRoute চালু নেই — Termux-এ চালাও",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    FilledTonalButton(onClick = {
                        if (ContextCompat.checkSelfPermission(
                                context, TERMUX_RUN_COMMAND_PERMISSION
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.runOmniRoute(true)
                        } else {
                            runPermission.launch(TERMUX_RUN_COMMAND_PERMISSION)
                        }
                    }) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Termux-এ চালাও")
                    }
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

            if (showSetup) {
                AgentSetupContent(
                    state = state,
                    modifier = Modifier.weight(1f),
                    onSave = { key, baseUrl, model ->
                        viewModel.saveAgentConfig(key, baseUrl, model)
                    },
                    onLoadModels = { viewModel.loadAgentModels() },
                    onSetModel = { viewModel.setAgentModel(it) },
                )
            } else {
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
                if (state.busy) {
                    LinearProgressIndicator(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    )
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
                            enabled = input.isNotBlank() && state.hasKey,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        }
    }
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
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
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
    onSave: (apiKey: String, baseUrl: String, model: String) -> Unit,
    onLoadModels: () -> Unit,
    onSetModel: (String) -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    var apiKey by remember { mutableStateOf("") }
    var baseUrl by remember(state.baseUrl) { mutableStateOf(state.baseUrl) }
    var model by remember(state.model) { mutableStateOf(state.model) }

    LazyColumn(
        modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Text(
                "AI চলে তোমার ফোনেই — OmniRoute (Termux-এ চলা free AI gateway)। " +
                    "একবার সেটআপ করলেই হবে:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            SetupStep(
                number = "1",
                title = "Termux install করো",
                body = "F-Droid থেকে নাও (Play Store-এর বিল্ড পুরনো):",
                action = {
                    OutlinedButton(onClick = { Intents.openUrl(context, F_DROID_TERMUX_URL) }) {
                        Text("F-Droid খোলো")
                    }
                },
            )
        }
        item {
            SetupStep(
                number = "2",
                title = "Termux-এ Node + OmniRoute install করো",
                body = null,
                code = OMNIROUTE_INSTALL_COMMAND,
                clipboard = clipboard,
            )
        }
        item {
            SetupStep(
                number = "3",
                title = "OmniRoute চালাও",
                body = "নিচের কমান্ডটা দাও, চালু থাকা অবস্থায় এই অ্যাপে ফিরে এসো। স্ক্রিন বন্ধ করলেও " +
                    "চলতে থাকবে; চাইলে Termux-এর notification থেকে \"Acquire wakelock\" দাও।",
                code = OMNIROUTE_RUN_COMMAND,
                secondCode = "mkdir -p ~/.termux && echo allow-external-apps=true >> ~/.termux/termux.properties",
                secondCodeNote = "\"Termux-এ চালাও\" বাটন ব্যবহার করতে চাইলে একবার এটাও দাও:",
                clipboard = clipboard,
            )
        }
        item {
            SetupStep(
                number = "4",
                title = "API key নাও",
                body = "ব্রাউজারে ড্যাশবোর্ড খোলো → পাসওয়ার্ড (প্রথমবার: ++CHANGEME) দিয়ে লগইন → " +
                    "সাথে সাথে পাসওয়ার্ড বদলে ফেলো → Endpoint পেজ থেকে API key copy করো।",
                action = {
                    OutlinedButton(onClick = { Intents.openUrl(context, OMNIROUTE_DASHBOARD_URL) }) {
                        Text("ড্যাশবোর্ড খোলো")
                    }
                },
            )
        }
        item {
            SetupStep(
                number = "5",
                title = "Key নিচে বসাও",
                body = null,
                action = {},
            )
        }
        item {
            Column {
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("OmniRoute API key") },
                    placeholder = { Text("or-…") },
                    singleLine = true,
                    isError = false,
                    supportingText = {
                        if (state.hasKey) {
                            Text("আগের key সেভ করা আছে — নতুনটা লিখলে বদলে যাবে")
                        } else {
                            Text("ড্যাশবোর্ডের Endpoint পেজ থেকে copy করো")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it },
                    label = { Text("Model") },
                    singleLine = true,
                    supportingText = {
                        Text("যেমন: auto/coding — বা \"Models লোড করো\" থেকে বেছে নাও")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (state.models.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "লোড হওয়া models:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    state.models.take(12).forEach { modelId ->
                        Text(
                            modelId,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { model = modelId }
                                .padding(vertical = 2.dp),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onLoadModels,
                        enabled = state.hasKey,
                        modifier = Modifier.weight(1f),
                    ) { Text("Models লোড করো") }
                    OutlinedButton(
                        onClick = { onSetModel(model) },
                        enabled = state.hasKey && model.isNotBlank(),
                        modifier = Modifier.weight(1f),
                    ) { Text("Model সেভ করো") }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    label = { Text("Base URL (অ্যাডভান্সড)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        onSave(
                            apiKey,
                            baseUrl.ifBlank { AiConfig.DEFAULT_BASE_URL },
                            model.ifBlank { AiConfig.DEFAULT_MODEL },
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) { Text("সেভ করে টেস্ট করো") }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun SetupStep(
    number: String,
    title: String,
    body: String?,
    code: String? = null,
    secondCode: String? = null,
    secondCodeNote: String? = null,
    action: @Composable () -> Unit = {},
    clipboard: androidx.compose.ui.platform.ClipboardManager? = null,
) {
    Row(Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = CircleShape,
        ) {
            Text(
                number,
                Modifier.padding(10.dp),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            body?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            code?.let { codeText ->
                Spacer(Modifier.height(6.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(
                                codeText,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        clipboard?.let { cm ->
                            IconButton(onClick = { cm.setText(AnnotatedString(codeText)) }) {
                                Icon(
                                    Icons.Rounded.ContentCopy,
                                    contentDescription = "Copy",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
            if (secondCode != null) {
                Spacer(Modifier.height(6.dp))
                secondCodeNote?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(2.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        SelectionContainer(Modifier.weight(1f)) {
                            Text(
                                secondCode,
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 10.sp,
                            )
                        }
                        clipboard?.let { cm ->
                            IconButton(onClick = { cm.setText(AnnotatedString(secondCode)) }) {
                                Icon(
                                    Icons.Rounded.ContentCopy,
                                    contentDescription = "Copy",
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            action()
        }
    }
}
