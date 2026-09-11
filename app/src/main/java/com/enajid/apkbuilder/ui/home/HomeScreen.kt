package com.enajid.apkbuilder.ui.home

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.enajid.apkbuilder.data.GithubRepo
import com.enajid.apkbuilder.ui.components.EmptyState
import com.enajid.apkbuilder.ui.components.ErrorState
import com.enajid.apkbuilder.ui.components.ScreenLoading
import com.enajid.apkbuilder.util.TimeUtils

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onCreate: () -> Unit,
    onOpen: (owner: String, repo: String) -> Unit,
    onKeystore: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmSignOut by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<GithubRepo?>(null) }
    var deleteTarget by remember { mutableStateOf<GithubRepo?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

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
                        Text("My Apps", style = MaterialTheme.typography.titleLarge)
                        state.login?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onKeystore) {
                        Icon(Icons.Rounded.Key, contentDescription = "Release signing")
                    }
                    IconButton(
                        onClick = { viewModel.load(showAsRefresh = true) },
                        enabled = !state.loading && !state.refreshing,
                    ) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "Refresh")
                    }
                    IconButton(onClick = { confirmSignOut = true }) {
                        Icon(Icons.AutoMirrored.Rounded.Logout, contentDescription = "Sign out")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onCreate,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("New app") },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            val error = state.error
            when {
                state.loading -> ScreenLoading("Loading your apps…")
                error != null -> ErrorState(message = error, onRetry = { viewModel.load() })
                state.projects.isEmpty() -> EmptyState(
                    icon = {
                        Icon(
                            imageVector = Icons.Rounded.RocketLaunch,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    },
                    title = "No apps yet",
                    subtitle = "Create your first app — pick a name and we'll set everything else up for you.",
                    actionLabel = "Create an app",
                    onAction = onCreate,
                )
                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(state.projects, key = { it.id }) { project ->
                        ProjectCard(
                            project = project,
                            menuOpen = menuFor?.id == project.id,
                            deleting = state.deletingRepoId == project.id,
                            onClick = {
                                val owner = project.owner?.login ?: project.full_name.substringBefore('/')
                                onOpen(owner, project.name)
                            },
                            onLongClick = { deleteTarget = project },
                            onMenuToggle = { open -> menuFor = if (open) project else null },
                            onDeleteClick = {
                                menuFor = null
                                deleteTarget = project
                            },
                        )
                    }
                }
            }
        }
    }

    if (confirmSignOut) {
        AlertDialog(
            onDismissRequest = { confirmSignOut = false },
            title = { Text("Sign out?") },
            text = { Text("Your projects stay safely on GitHub — you can sign back in any time.") },
            confirmButton = {
                TextButton(onClick = { confirmSignOut = false; viewModel.signOut() }) {
                    Text("Sign out")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmSignOut = false }) { Text("Cancel") }
            },
        )
    }

    deleteTarget?.let { project ->
        val deleting = state.deletingRepoId == project.id
        AlertDialog(
            onDismissRequest = { if (!deleting) deleteTarget = null },
            title = { Text("Delete “${project.name}”?") },
            text = {
                Text(
                    "This permanently deletes the GitHub repository " +
                        "${project.full_name} — all code, history and build artifacts. " +
                        "This cannot be undone."
                )
            },
            confirmButton = {
                if (deleting) {
                    Box(Modifier.padding(12.dp)) {
                        CircularProgressIndicator(Modifier.size(22.dp))
                    }
                } else {
                    TextButton(
                        onClick = { viewModel.deleteProject(project) },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) { Text("Delete permanently") }
                }
            },
            dismissButton = {
                if (!deleting) {
                    TextButton(onClick = { deleteTarget = null }) { Text("Cancel") }
                }
            },
        )
    }

    if (state.reauthNeeded) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissReauth() },
            title = { Text("One more permission needed") },
            text = {
                Text(
                    "Deleting projects needs the “delete repositories” permission, which " +
                        "wasn’t part of the permissions you granted at sign-in. Sign out and " +
                        "sign back in to grant it — your projects stay untouched.\n\n" +
                        "(You can also always delete repositories directly on github.com.)"
                )
            },
            confirmButton = {
                Button(onClick = { viewModel.dismissReauth(); viewModel.signOut() }) {
                    Text("Sign out & re-connect")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissReauth() }) { Text("Not now") }
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ProjectCard(
    project: GithubRepo,
    menuOpen: Boolean,
    deleting: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onMenuToggle: (Boolean) -> Unit,
    onDeleteClick: () -> Unit,
) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(start = 16.dp, top = 16.dp, bottom = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = project.name.trim().take(1).uppercase().ifBlank { "A" },
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    project.description ?: project.full_name,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "Updated ${TimeUtils.relativeTime(project.pushed_at ?: project.updated_at)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                if (deleting) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            .size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    IconButton(onClick = { onMenuToggle(true) }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "Options")
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { onMenuToggle(false) },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Delete project") },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Delete,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                )
                            },
                            onClick = onDeleteClick,
                        )
                    }
                }
            }
        }
    }
}
