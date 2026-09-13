package com.enajid.apkbuilder.ui.build

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enajid.apkbuilder.domain.MappedStep
import com.enajid.apkbuilder.domain.StepStatus
import com.enajid.apkbuilder.ui.build.BuildStatusViewModel.Phase
import com.enajid.apkbuilder.ui.components.ErrorState
import com.enajid.apkbuilder.ui.components.ScreenLoading
import com.enajid.apkbuilder.util.Format
import com.enajid.apkbuilder.util.Intents
import com.enajid.apkbuilder.util.QrBitmap
import com.enajid.apkbuilder.util.TimeUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildStatusScreen(
    owner: String,
    repo: String,
    onBack: () -> Unit,
    onEditCode: () -> Unit,
    onFixWithAi: (String) -> Unit = {},
    viewModel: BuildStatusViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // On Android 9 and below, copying into the public Downloads folder needs
    // this legacy permission; ask for it just-in-time when downloading.
    val writePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.downloadApk() }

    LaunchedEffect(state.error) {
        state.error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeError()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Building ${state.repoName.ifBlank { repo }}") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            val error = state.error
            when (state.phase) {
                Phase.ERROR -> ErrorState(
                    message = error ?: "Something went wrong.",
                    onRetry = { viewModel.start() },
                )

                else -> Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (state.phase) {
                        Phase.PUSHING, Phase.WAITING, Phase.RUNNING -> BuildProgressCard(state)
                        Phase.SUCCESS -> ApkReadyCard(
                            state = state,
                            onRetry = { viewModel.start() },
                            onDownload = {
                                val needsLegacyPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                                val granted = ContextCompat.checkSelfPermission(
                                    context, Manifest.permission.WRITE_EXTERNAL_STORAGE
                                ) == PackageManager.PERMISSION_GRANTED
                                if (needsLegacyPermission && !granted) {
                                    writePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                } else {
                                    viewModel.downloadApk()
                                }
                            },
                            onInstall = { downloaded ->
                                Intents.installApk(context, downloaded.uri)
                            },
                            onShare = { downloaded ->
                                Intents.shareApk(context, downloaded.uri, downloaded.displayName)
                            },
                        )
                        Phase.FAILURE -> BuildFailedCard(
                            state = state,
                            onRetry = { viewModel.start() },
                            onEditCode = onEditCode,
                            onFixWithAi = onFixWithAi,
                        )
                        Phase.ERROR -> Unit
                    }

                    state.slowBuildNote?.let { note ->
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    note,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                        }
                    }

                    if (state.phase == Phase.PUSHING || state.phase == Phase.WAITING || state.phase == Phase.RUNNING) {
                        StepList(state)
                        Text(
                            "This screen updates itself every few seconds while GitHub works — " +
                                "no need to refresh.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- progress --

@Composable
private fun BuildProgressCard(state: BuildStatusViewModel.BuildUiState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = when (state.phase) {
                            Phase.PUSHING -> "Saving your code…"
                            Phase.WAITING -> "Waiting for GitHub…"
                            else -> "Compiling on GitHub Actions…"
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    state.runNumber?.let {
                        Text(
                            "Run #$it",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                state.elapsedMs?.let {
                    Text(
                        TimeUtils.formatDuration(it),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun StepList(state: BuildStatusViewModel.BuildUiState) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        // Two app-side steps that reflect what we did, not faked CI progress.
        StepRow(
            MappedStep(
                label = "Saving your code",
                realName = "git push",
                status = if (state.phase == Phase.PUSHING) StepStatus.RUNNING else StepStatus.SUCCESS,
                major = true,
            )
        )
        StepRow(
            MappedStep(
                label = "GitHub picked up your build",
                realName = "workflow run",
                status = when (state.phase) {
                    Phase.PUSHING -> StepStatus.QUEUED
                    Phase.WAITING -> StepStatus.RUNNING
                    else -> StepStatus.SUCCESS
                },
                major = true,
            )
        )
        HorizontalDivider(Modifier.padding(vertical = 6.dp))

        if (state.phase == Phase.RUNNING && state.steps.isEmpty()) {
            StepRow(
                MappedStep(
                    label = "Preparing the build job…",
                    realName = "queued on GitHub",
                    status = StepStatus.RUNNING,
                    major = true,
                )
            )
        } else {
            state.steps.forEach { step -> StepRow(step) }
        }
    }
}

@Composable
private fun StepRow(step: MappedStep) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepStatusIcon(step.status, major = step.major)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = step.label,
                style = if (step.major) {
                    MaterialTheme.typography.bodyLarge
                } else {
                    MaterialTheme.typography.bodySmall
                },
                color = when (step.status) {
                    StepStatus.FAILURE -> MaterialTheme.colorScheme.error
                    StepStatus.SUCCESS -> MaterialTheme.colorScheme.onSurface
                    else -> MaterialTheme.colorScheme.onSurface.copy(
                        alpha = if (step.major) 1f else 0.7f
                    )
                },
            )
            if (step.major && step.realName.isNotBlank()) {
                Text(
                    step.realName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        step.durationMs?.let {
            Text(
                TimeUtils.formatDuration(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StepStatusIcon(status: StepStatus, major: Boolean) {
    val size = if (major) 24.dp else 18.dp
    when (status) {
        StepStatus.SUCCESS -> Icon(
            Icons.Rounded.CheckCircle,
            contentDescription = "Done",
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.size(size),
        )
        StepStatus.RUNNING -> CircularProgressIndicator(
            modifier = Modifier.size(if (major) 22.dp else 16.dp),
            strokeWidth = 2.dp,
        )
        StepStatus.FAILURE -> Icon(
            Icons.Rounded.Error,
            contentDescription = "Failed",
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(size),
        )
        StepStatus.SKIPPED -> Icon(
            Icons.Rounded.Remove,
            contentDescription = "Skipped",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size),
        )
        StepStatus.CANCELLED -> Icon(
            Icons.Rounded.Close,
            contentDescription = "Cancelled",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(size),
        )
        StepStatus.QUEUED, StepStatus.NEUTRAL -> Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

// ----------------------------------------------------------------- success --

@Composable
private fun ApkReadyCard(
    state: BuildStatusViewModel.BuildUiState,
    onRetry: () -> Unit,
    onDownload: () -> Unit,
    onInstall: (BuildStatusViewModel.DownloadedApk) -> Unit,
    onShare: (BuildStatusViewModel.DownloadedApk) -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(56.dp),
            )
            Spacer(Modifier.height(8.dp))
            Text("Your APK is ready 🎉", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(4.dp))
            if (state.artifactName != null) {
                Text(
                    "${state.artifactName} · ${state.artifactSizeBytes?.let { Format.bytes(it) } ?: "?"}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.artifactExpiresInDays?.let { days ->
                    Text(
                        "⏳ ~$days দিন পর্যন্ত GitHub-এ থাকবে (90 দিন পরে artifact মুছে যায়)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            state.elapsedMs?.let {
                Text(
                    "Built in ${TimeUtils.formatDuration(it)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(16.dp))

            val downloaded = state.downloaded
            when {
                state.artifactId == null || state.artifactExpired -> {
                    Text(
                        "The artifact is no longer available — GitHub keeps build artifacts " +
                            "for 90 days. Run a new build to get a fresh APK.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                        Text("Build again")
                    }
                }

                state.downloading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Downloading APK…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                downloaded == null -> Button(
                    onClick = onDownload,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Download APK")
                }

                else -> {
                    Text(
                        "${downloaded.displayName} · ${Format.bytes(downloaded.sizeBytes)}",
                        style = MaterialTheme.typography.labelMedium,
                        fontFamily = FontFamily.Monospace,
                    )
                    if (downloaded.savedToDownloads) {
                        Text(
                            "Saved to Downloads/APK Builder",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { onInstall(downloaded) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                    ) {
                        Text("Install on this phone")
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { onShare(downloaded) }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Share")
                        }
                        OutlinedButton(onClick = onDownload, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Re-download")
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    state.runUrl?.let { url ->
                        QrSection(url)
                    }
                }
            }
        }
    }
}

@Composable
private fun QrSection(url: String) {
    val qr = remember(url) { QrBitmap.generate(url, size = 480).asImageBitmap() }
    Image(
        bitmap = qr,
        contentDescription = "QR code linking to this build",
        modifier = Modifier
            .size(148.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White)
            .padding(8.dp),
    )
    Spacer(Modifier.height(6.dp))
    Text(
        "Scan to open this build on GitHub from another device",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
    )
}

// ----------------------------------------------------------------- failure --

@Composable
private fun BuildFailedCard(
    state: BuildStatusViewModel.BuildUiState,
    onRetry: () -> Unit,
    onEditCode: () -> Unit,
    onFixWithAi: (String) -> Unit,
) {
    val context = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Error,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(40.dp),
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text("Build failed", style = MaterialTheme.typography.titleLarge)
                    Text(
                        state.failureHeading ?: "GitHub reported a failing step",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (state.failureLines.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    state.failureLines.forEach { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    onFixWithAi(
                        buildString {
                            append("The last GitHub Actions build failed")
                            state.failureHeading?.let { append(": "); append(it) }
                            append(".\n")
                            state.failureLines.take(30).forEach { appendLine(it) }
                            append(
                                "\nRead the relevant files first, find the cause, then fix the code " +
                                    "so it builds."
                            )
                        }
                    )
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Rounded.SmartToy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("AI agent দিয়ে ঠিক করো")
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Text("Try again")
            }
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onEditCode, modifier = Modifier.weight(1f)) {
                    Text("Edit code")
                }
                state.runUrl?.let { url ->
                    OutlinedButton(onClick = { Intents.openUrl(context, url) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Full logs")
                    }
                }
            }
        }
    }
}
