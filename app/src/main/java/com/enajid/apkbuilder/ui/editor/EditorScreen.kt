package com.enajid.apkbuilder.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enajid.apkbuilder.ui.components.ErrorState
import com.enajid.apkbuilder.ui.components.ScreenLoading
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(
    owner: String,
    repo: String,
    onBack: () -> Unit,
    onBuild: () -> Unit,
    viewModel: EditorViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val loadedFile by viewModel.selectedFile.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    var drawerOpen by remember { mutableStateOf(false) }
    var exitDialog by remember { mutableStateOf(false) }
    var newFileDialog by remember { mutableStateOf(false) }

    // Local text state, re-initialized whenever a different file is loaded.
    var field by remember(loadedFile?.path) {
        mutableStateOf(TextFieldValue(loadedFile?.content ?: ""))
    }

    BackHandler(enabled = state.dirty.isNotEmpty()) { exitDialog = true }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            state.repoName.ifBlank { repo },
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { drawerOpen = true }) {
                        Icon(Icons.Rounded.Folder, contentDescription = "Files")
                    }
                    IconButton(
                        onClick = { scope.launch { viewModel.save() } },
                        enabled = state.dirty.isNotEmpty() && !state.saving,
                    ) {
                        Icon(Icons.Rounded.Save, contentDescription = "Save to GitHub")
                    }
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                if (viewModel.commitPending()) onBuild()
                            }
                        },
                        enabled = !state.saving,
                        modifier = Modifier.padding(end = 12.dp),
                    ) {
                        Icon(Icons.Rounded.Build, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Build")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            val error = state.error
            when {
                state.loading -> ScreenLoading("Loading project…")
                error != null -> ErrorState(message = error, onRetry = { viewModel.load() })
                else -> Column(
                    Modifier
                        .fillMaxSize()
                        .imePadding()
                ) {
                    EditorStatusStrip(state = state, field = field)
                    HorizontalDivider()
                    Box(Modifier.weight(1f)) {
                        val currentFile = loadedFile
                        when {
                            state.fileLoading ->
                                ScreenLoading("Opening ${state.selectedPath?.substringAfterLast('/') ?: "file"}…")
                            state.selectedIsBinary -> BinaryPlaceholder()
                            currentFile != null -> CodeEditorField(
                                value = field,
                                onValueChange = { newValue ->
                                    field = applySmartEditing(field, newValue)
                                    viewModel.onContentChange(field.text)
                                },
                                language = inferLanguage(currentFile.path),
                            )
                            else -> NoFileSelected(onOpenFiles = { drawerOpen = true })
                        }

                        FileTreeDrawer(
                            open = drawerOpen,
                            paths = state.paths,
                            dirty = state.dirty,
                            selectedPath = state.selectedPath,
                            onSelect = {
                                viewModel.select(it)
                                drawerOpen = false
                            },
                            onClose = { drawerOpen = false },
                            onNewFile = { newFileDialog = true },
                        )
                    }
                }
            }

            if (state.saving) {
                ScreenLoading("Saving your changes…")
            }
        }
    }

    if (exitDialog) {
        AlertDialog(
            onDismissRequest = { exitDialog = false },
            title = { Text("Unsaved changes") },
            text = { Text("You have ${state.dirty.size} file(s) with unsaved edits. Save them to GitHub before leaving?") },
            confirmButton = {
                TextButton(onClick = {
                    exitDialog = false
                    scope.launch {
                        if (viewModel.commitPending()) onBack()
                    }
                }) { Text("Save & leave") }
            },
            dismissButton = {
                TextButton(onClick = {
                    exitDialog = false
                    onBack()
                }) { Text("Discard") }
            },
        )
    }

    if (newFileDialog) {
        NewFileDialog(
            onDismiss = { newFileDialog = false },
            onCreate = { path ->
                newFileDialog = false
                viewModel.newFile(path)
            },
        )
    }
}

@Composable
private fun EditorStatusStrip(state: EditorViewModel.EditorUiState, field: TextFieldValue) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            state.selectedPath ?: "No file selected",
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (state.dirty.isNotEmpty()) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
            Text(
                "${state.dirty.size} unsaved",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Text(
            cursorLabel(field),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun BinaryPlaceholder() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("🔐", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(8.dp))
            Text("This is a binary file", style = MaterialTheme.typography.titleSmall)
            Text(
                "Images, jars and other binaries can't be edited here (yet).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun NoFileSelected(onOpenFiles: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("No file open", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            TextButton(onClick = onOpenFiles) { Text("Browse files") }
        }
    }
}

@Composable
private fun NewFileDialog(
    onDismiss: () -> Unit,
    onCreate: (String) -> Unit,
) {
    var path by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New file") },
        text = {
            OutlinedTextField(
                value = path,
                onValueChange = { path = it },
                singleLine = true,
                label = { Text("Path in the project") },
                placeholder = { Text("app/src/main/java/…/MyFile.kt", maxLines = 1) },
            )
        },
        confirmButton = {
            TextButton(onClick = { onCreate(path) }) { Text("Create") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** "Ln 12, Col 8" from the current selection. */
private fun cursorLabel(value: TextFieldValue): String {
    val text = value.text
    val position = value.selection.min.coerceIn(0, text.length)
    val before = text.substring(0, position)
    val line = before.count { it == '\n' } + 1
    val column = before.length - (before.lastIndexOf('\n') + 1) + 1
    return "Ln $line, Col $column"
}

/**
 * Small quality-of-life edits: pressing Enter copies the previous line's
 * indentation (plus one level after an opening brace), like a real editor.
 */
internal fun applySmartEditing(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    if (new.composition != null) return new
    if (new.text.length != old.text.length + 1) return new
    val insertionAt = new.selection.min - 1
    if (insertionAt < 0 || new.text[insertionAt] != '\n') return new

    val before = new.text.substring(0, insertionAt)
    val currentLine = before.substringAfterLast('\n')
    val indent = currentLine.takeWhile { it == ' ' || it == '\t' }
    val extra = if (currentLine.trimEnd().endsWith("{")) "    " else ""
    val insert = indent + extra
    if (insert.isEmpty()) return new

    val withIndent = StringBuilder(new.text).insert(insertionAt + 1, insert).toString()
    val caret = insertionAt + 1 + insert.length
    return new.copy(text = withIndent, selection = TextRange(caret))
}
