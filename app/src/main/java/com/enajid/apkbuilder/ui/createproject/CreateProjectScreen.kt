package com.enajid.apkbuilder.ui.createproject

import android.graphics.BitmapFactory

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enajid.apkbuilder.data.ProjectCreator
import com.enajid.apkbuilder.domain.Framework
import com.enajid.apkbuilder.domain.PackageNames

private val MIN_SDK_OPTIONS = listOf(24, 26, 28, 29, 31, 33, 34)
private val TARGET_SDK_OPTIONS = listOf(34, 33, 31, 29)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateProjectScreen(
    onCreated: (owner: String, repo: String) -> Unit,
    onCancel: () -> Unit,
    viewModel: CreateProjectViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    val iconPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) viewModel.onIconPicked(uri) }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }
    LaunchedEffect(state.created) {
        state.created?.let { (owner, repo) ->
            viewModel.onNavigated()
            onCreated(owner, repo)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("New app") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Spacer(Modifier.height(8.dp))

                OutlinedTextField(
                    value = state.appName,
                    onValueChange = viewModel::setAppName,
                    label = { Text("App name") },
                    supportingText = {
                        Text(
                            if (state.login != null) {
                                "Will live at github.com/${state.login}/${viewModel.suggestedRepoName()}"
                            } else {
                                "Shown under your app's icon"
                            }
                        )
                    },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Rounded.Smartphone, contentDescription = null) },
                    modifier = Modifier.fillMaxWidth(),
                )

                IconPickerRow(
                    iconBytes = state.iconBytes,
                    onPick = {
                        iconPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    onReset = { viewModel.clearIcon() },
                )

                val packageError = if (state.packageName.isNotBlank() && !PackageNames.isValid(state.packageName)) {
                    PackageNames.validationError(state.packageName) ?: "Not a valid package name"
                } else null

                OutlinedTextField(
                    value = state.packageName,
                    onValueChange = viewModel::setPackageName,
                    label = { Text("Package name") },
                    placeholder = { Text("com.myapp") },
                    isError = packageError != null,
                    supportingText = {
                        Text(
                            packageError
                                ?: "Unique ID for your app, like com.example.myapp"
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(Modifier.height(8.dp))
                Text(
                    "Language",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Framework.entries.forEach { framework ->
                    FrameworkCard(
                        framework = framework,
                        selected = state.framework == framework,
                        onSelect = { viewModel.setFramework(framework) },
                    )
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    "Android version support",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SdkDropdown(
                        label = "Minimum",
                        value = state.minSdk,
                        options = MIN_SDK_OPTIONS,
                        enabled = { true },
                        onSelected = viewModel::setMinSdk,
                        modifier = Modifier.weight(1f),
                    )
                    SdkDropdown(
                        label = "Target",
                        value = state.targetSdk,
                        options = TARGET_SDK_OPTIONS,
                        enabled = { it >= state.minSdk },
                        onSelected = viewModel::setTargetSdk,
                        modifier = Modifier.weight(1f),
                    )
                }
                Text(
                    "Minimum = oldest Android that can run your app. Target = newest " +
                        "Android it's tuned for. Defaults are fine for most apps.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(Modifier.height(16.dp))
                Button(
                    onClick = viewModel::create,
                    enabled = state.appName.isNotBlank() && PackageNames.isValid(state.packageName),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text("Create app")
                }
                Spacer(Modifier.height(24.dp))
            }

            if (state.creating) {
                CreatingOverlay(step = state.creatingStep)
            }
        }
    }
}

@Composable
private fun CreatingOverlay(step: ProjectCreator.Step?) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator()
            Spacer(Modifier.height(16.dp))
            Text(
                text = when (step) {
                    ProjectCreator.Step.CREATING_REPO -> "Creating your GitHub repository…"
                    ProjectCreator.Step.UPLOADING_CODE -> "Uploading your project…"
                    ProjectCreator.Step.FINISHING, null -> "Finishing up…"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

@Composable
private fun IconPickerRow(
    iconBytes: ByteArray?,
    onPick: () -> Unit,
    onReset: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = remember(iconBytes) {
                iconBytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Icon(
                    imageVector = Icons.Rounded.Smartphone,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("App icon", style = MaterialTheme.typography.bodyLarge)
            Text(
                "Optional — tap to choose an image",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (iconBytes != null) {
            TextButton(onClick = onReset) { Text("Reset") }
        }
    }
}

@Composable
private fun FrameworkCard(
    framework: Framework,
    selected: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        onClick = onSelect,
        enabled = framework.available,
        modifier = Modifier.fillMaxWidth(),
        colors = if (selected) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        } else {
            CardDefaults.cardColors()
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = null, enabled = framework.available)
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(framework.label, style = MaterialTheme.typography.titleSmall)
                    if (!framework.available) {
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Coming soon",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
                Text(
                    framework.blurb,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun SdkDropdown(
    label: String,
    value: Int,
    options: List<Int>,
    enabled: (Int) -> Boolean,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                "$label: API $value",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.width(4.dp))
            Icon(Icons.Rounded.ExpandMore, contentDescription = null)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text("API $option") },
                    enabled = enabled(option),
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}
