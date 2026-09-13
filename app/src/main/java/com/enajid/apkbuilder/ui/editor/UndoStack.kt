package com.enajid.apkbuilder.ui.editor

import androidx.compose.ui.text.input.TextFieldValue

/**
 * Snapshot-based undo/redo for the code editor.
 *
 * Rapid single-char typing is coalesced into one undo step: while changes
 * keep arriving within [COALESCE_MS] of the last push, only the burst's
 * starting snapshot is kept. A clock can be injected for deterministic tests.
 */
class UndoStack(
    private val limit: Int = 150,
    private val now: () -> Long = System::currentTimeMillis,
) {

    private val undo = mutableListOf<TextFieldValue>()
    private val redo = mutableListOf<TextFieldValue>()
    private var lastPushAt = 0L

    val canUndo: Boolean get() = undo.isNotEmpty()
    val canRedo: Boolean get() = redo.isNotEmpty()

    /** Call with the value BEFORE a change is applied. */
    fun push(previous: TextFieldValue) {
        val timestamp = now()
        val withinBurst = timestamp - lastPushAt < COALESCE_MS
        lastPushAt = timestamp
        if (withinBurst && undo.isNotEmpty()) {
            // Typing burst: keep the burst's first snapshot as-is.
            return
        }
        if (undo.isNotEmpty() && undo.last().text == previous.text) return
        undo += previous
        while (undo.size > limit) undo.removeAt(0)
        redo.clear() // a new edit invalidates the redo branch
    }

    /** Returns the state to restore, or null when there's nothing to undo. */
    fun undo(current: TextFieldValue): TextFieldValue? {
        if (undo.isEmpty()) return null
        redo += current
        return undo.removeAt(undo.lastIndex)
    }

    /** Returns the state to restore, or null when there's nothing to redo. */
    fun redo(current: TextFieldValue): TextFieldValue? {
        if (redo.isEmpty()) return null
        undo += current
        lastPushAt = 0L // the next push starts a fresh burst
        return redo.removeAt(redo.lastIndex)
    }

    /** File switched / replaced: history no longer applies. */
    fun reset() {
        undo.clear()
        redo.clear()
        lastPushAt = 0L
    }

    companion object {
        const val COALESCE_MS = 900L
    }
}
