package com.enajid.apkbuilder.ui.keystore

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enajid.apkbuilder.data.signing.KeystoreEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Release-signing keystore manager: create a self-signed keystore on the
 * phone or import one made elsewhere. The active keystore is automatically
 * pushed to every app repo so GitHub Actions can sign release APKs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeystoreScreen(
    onBack: () -> Unit,
    viewModel: KeystoreViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var showCreate by remember { mutableStateOf(false) }
    var showImport by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<KeystoreEntry?>(null) }
    var pendingBytesPath by remember { mutableStateOf<android.net.Uri?>(null) }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            pendingBytesPath = uri
            showImport = true
        }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Release signing") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showCreate = true },
                icon = { Icon(Icons.Rounded.Key, contentDescription = null) },
                text = { Text("নতুন keystore") },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "সব app-এর release APK একই keystore দিয়ে sign হয় — একবার সেট করলে প্রতিটা Build-এ " +
                    "signed APK (`app-release` artifact) পাবে। একই keystore দিয়ে sign করা update " +
                    "আগের app-এর উপরেই install হয়।",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Rounded.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "signing চালু থাকলে keystore + password তোমার প্রতিটা app repo-তে (public) " +
                            "সংরক্ষিত হয় — GitHub Actions সেখান থেকে sign করে। Hobby app-এর জন্য ঠিক, " +
                            "সিরিয়াস publishing-এ নিজের private repo ব্যবহার করো।",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
            }

            Text("Keystores", style = MaterialTheme.typography.titleMedium)

            if (state.loading) {
                CircularProgressIndicator(Modifier.size(24.dp))
            } else if (state.entries.isEmpty()) {
                Text(
                    "এখনো কোনো keystore নেই — নতুন বানাও বা বাইরে থেকে import করো।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                state.entries.forEach { entry ->
                    KeystoreRow(
                        entry = entry,
                        active = entry.name == state.activeName,
                        onActivate = { viewModel.setActive(entry.name) },
                        onDelete = { deleteTarget = entry },
                    )
                }
            }

            OutlinedButton(onClick = { filePicker.launch("*/*") }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("অন্য জায়গায় বানানো keystore import করো (.jks/.p12)")
            }
        }
    }

    if (showCreate) {
        CreateKeystoreDialog(
            busy = state.busy,
            onDismiss = { showCreate = false },
            onCreate = { name, alias, storePass, keyPass, cn, org ->
                viewModel.create(name, alias, storePass, keyPass, cn, org)
                showCreate = false
            },
        )
    }

    if (showImport && pendingBytesPath != null) {
        ImportKeystoreDialog(
            busy = state.busy,
            suggestedName = "imported-keystore",
            onDismiss = {
                showImport = false
                pendingBytesPath = null
            },
            onImport = { name, storePass, keyPass, alias ->
                pendingBytesPath?.let { uri ->
                    viewModel.import(name, uri, storePass, keyPass, alias)
                }
                showImport = false
                pendingBytesPath = null
            },
        )
    }

    deleteTarget?.let { entry ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Keystore মুছে ফেলবে?") },
            text = {
                Text(
                    "\"${entry.name}\" মুছে গেলে ভবিষ্যতের build আর এটা দিয়ে sign হবে না। " +
                        "এই keystore দিয়ে sign করা app-এর update দিতে হলে এটাই আবার লাগবে — " +
                        "file টা বাইরে save করে রাখা না থাকলে হারিয়ে যাবে!"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(entry.name)
                    deleteTarget = null
                }) { Text("মুছে দাও") }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("না") }
            },
        )
    }
}

@Composable
private fun KeystoreRow(
    entry: KeystoreEntry,
    active: Boolean,
    onActivate: () -> Unit,
    onDelete: () -> Unit,
) {
    val created = remember(entry.createdAt) {
        if (entry.createdAt == 0L) "" else {
            SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(entry.createdAt))
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (active) Icons.Rounded.CheckCircle else Icons.Rounded.Key,
                    contentDescription = null,
                    tint = if (active) MaterialTheme.colorScheme.tertiary else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.name, style = MaterialTheme.typography.titleSmall)
                    Text(
                        buildString {
                            append("alias: ").append(entry.alias)
                            append(if (entry.imported) " · imported" else " · তৈরি এখানেই")
                            if (created.isNotBlank()) append(" · $created")
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Rounded.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (active) {
                Text(
                    "✓ সব app-এর build-এ ব্যবহার হচ্ছে",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            } else {
                TextButton(onClick = onActivate) { Text("এটা ব্যবহার করো") }
            }
        }
    }
}

@Composable
private fun CreateKeystoreDialog(
    busy: Boolean,
    onDismiss: () -> Unit,
    onCreate: (name: String, alias: String, storePass: String, keyPass: String, cn: String, org: String) -> Unit,
) {
    var name by remember { mutableStateOf("my-release-key") }
    var alias by remember { mutableStateOf("release") }
    var storePass by remember { mutableStateOf("") }
    var keyPass by remember { mutableStateOf("") }
    var cn by remember { mutableStateOf("") }
    var org by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("নতুন keystore বানাও") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "ফোনেই তৈরি হয় (RSA 2048, ৩০ বছর মেয়াদ)। Password গুলো মনে রেখো / নোট করে রাখো — " +
                        "ভবিষ্যতে অন্য ফোন থেকে এই keystore ব্যবহার করতে চাইলে লাগবে।",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("নাম") }, singleLine = true)
                OutlinedTextField(value = alias, onValueChange = { alias = it }, label = { Text("Key alias") }, singleLine = true)
                OutlinedTextField(
                    value = storePass, onValueChange = { storePass = it },
                    label = { Text("Keystore password (৬+ অক্ষর)") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = keyPass, onValueChange = { keyPass = it },
                    label = { Text("Key password") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(value = cn, onValueChange = { cn = it }, label = { Text("Certificate: তোমার নাম (optional)") }, singleLine = true)
                OutlinedTextField(value = org, onValueChange = { org = it }, label = { Text("Certificate: সংস্থা (optional)") }, singleLine = true)
                if (busy) CircularProgressIndicator(Modifier.size(20.dp))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name, alias, storePass, keyPass, cn, org) },
                enabled = !busy && name.isNotBlank() && alias.isNotBlank() &&
                    storePass.length >= 6 && keyPass.isNotBlank(),
            ) { Text("তৈরি করো") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("না") } },
    )
}

@Composable
private fun ImportKeystoreDialog(
    busy: Boolean,
    suggestedName: String,
    onDismiss: () -> Unit,
    onImport: (name: String, storePass: String, keyPass: String, alias: String) -> Unit,
) {
    var name by remember { mutableStateOf(suggestedName) }
    var storePass by remember { mutableStateOf("") }
    var keyPass by remember { mutableStateOf("") }
    var alias by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keystore import করো") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "keytool/Android Studio-তে বানানো .jks বা .p12 ফাইল বেছে নেওয়া হয়েছে — এখন " +
                        "password দাও (alias খালি রাখলে নিজেই খুঁজে নেবে)।",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("নাম") }, singleLine = true)
                OutlinedTextField(
                    value = storePass, onValueChange = { storePass = it },
                    label = { Text("Keystore password") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = keyPass, onValueChange = { keyPass = it },
                    label = { Text("Key password") },
                    singleLine = true, visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = alias, onValueChange = { alias = it },
                    label = { Text("Key alias (optional)") },
                    singleLine = true,
                )
                if (busy) CircularProgressIndicator(Modifier.size(20.dp))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onImport(name, storePass, keyPass, alias) },
                enabled = !busy && name.isNotBlank() && storePass.isNotBlank(),
            ) { Text("Import করো") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("না") } },
    )
}
