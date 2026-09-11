package com.enajid.apkbuilder.ui.editor

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Arrangement

data class FileNode(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val children: MutableList<FileNode> = mutableListOf(),
)

/** Builds a directory tree from a list of file paths. */
fun buildFileTree(paths: List<String>): List<FileNode> {
    val root = FileNode("", "", isDir = true)
    for (path in paths.sorted()) {
        var current = root
        val parts = path.split('/')
        parts.forEachIndexed { index, part ->
            val isFile = index == parts.lastIndex
            val fullPath = parts.subList(0, index + 1).joinToString("/")
            val existing = current.children.firstOrNull { it.name == part }
            current = if (existing != null) {
                existing
            } else {
                val node = FileNode(part, fullPath, isDir = !isFile)
                current.children.add(node)
                node
            }
        }
    }
    sortTree(root)
    return root.children
}

private fun sortTree(node: FileNode) {
    node.children.sortWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
    node.children.forEach { sortTree(it) }
}

private data class FlatNode(val node: FileNode, val depth: Int)

private fun flattenTree(roots: List<FileNode>, expanded: Set<String>): List<FlatNode> {
    val out = mutableListOf<FlatNode>()
    fun visit(node: FileNode, depth: Int) {
        out += FlatNode(node, depth)
        if (node.isDir && node.path in expanded) {
            node.children.forEach { visit(it, depth + 1) }
        }
    }
    roots.forEach { visit(it, 0) }
    return out
}

/** Left-side file drawer shown over the editor. */
@Composable
fun FileTreeDrawer(
    open: Boolean,
    paths: List<String>,
    dirty: Set<String>,
    selectedPath: String?,
    onSelect: (String) -> Unit,
    onFileLongPress: (String) -> Unit,
    onClose: () -> Unit,
    onNewFile: () -> Unit,
) {
    AnimatedVisibility(
        visible = open,
        enter = slideInHorizontally { -it } + fadeIn(),
        exit = slideOutHorizontally { -it } + fadeOut(),
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        Box(Modifier.fillMaxSize()) {
            // Scrim: tap outside the drawer to close it.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { onClose() }
            )
            Column(
                Modifier
                    .fillMaxHeight()
                    .width(300.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                    ) { /* consume taps so the drawer doesn't close */ }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Files",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onNewFile) {
                        Icon(Icons.Rounded.Add, contentDescription = "New file")
                    }
                    IconButton(onClick = onClose) {
                        Icon(Icons.Rounded.Close, contentDescription = "Close")
                    }
                }
                HorizontalDivider()
                if (paths.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "No files yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else {
                    val tree = remember(paths) { buildFileTree(paths) }
                    var expanded by remember(tree) {
                        mutableStateOf(tree.filter { it.isDir }.map { it.path }.toSet())
                    }
                    val flat = remember(tree, expanded) { flattenTree(tree, expanded) }
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        items(flat, key = { it.node.path }) { item ->
                            FileRow(
                                node = item.node,
                                depth = item.depth,
                                expanded = item.node.path in expanded,
                                selected = item.node.path == selectedPath,
                                dirty = item.node.path in dirty,
                                onClick = {
                                    if (item.node.isDir) {
                                        expanded = if (item.node.path in expanded) {
                                            expanded - item.node.path
                                        } else {
                                            expanded + item.node.path
                                        }
                                    } else {
                                        onSelect(item.node.path)
                                    }
                                },
                                onLongClick = {
                                    if (!item.node.isDir) onFileLongPress(item.node.path)
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileRow(
    node: FileNode,
    depth: Int,
    expanded: Boolean,
    selected: Boolean,
    dirty: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(
                start = 12.dp + (depth * 14).dp,
                end = 12.dp,
                top = 2.dp,
                bottom = 2.dp,
            )
            .height(36.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = when {
                node.isDir && expanded -> Icons.Rounded.FolderOpen
                node.isDir -> Icons.Rounded.Folder
                else -> Icons.Rounded.Description
            },
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        )
        Text(
            node.name,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) {
                androidx.compose.ui.text.font.FontWeight.SemiBold
            } else {
                androidx.compose.ui.text.font.FontWeight.Normal
            },
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (dirty) {
            Box(
                Modifier
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
    }
}
