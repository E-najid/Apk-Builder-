package com.enajid.apkbuilder.ui.editor

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UndoStackTest {

    private fun value(text: String, cursor: Int = text.length) =
        TextFieldValue(text, TextRange(cursor))

    @Test
    fun `typing burst coalesces into one undo step`() {
        var clock = 0L
        val stack = UndoStack(now = { clock })

        stack.push(value("H"))
        clock = 100
        stack.push(value("He"))
        clock = 200
        stack.push(value("Hel"))

        val restored = stack.undo(value("Hello"))
        assertEquals("H", restored?.text)
        // nothing more to undo within this burst
        assertNull(stack.undo(value("H")))
    }

    @Test
    fun `separate edits are separate undo steps`() {
        var clock = 0L
        val stack = UndoStack(now = { clock })

        stack.push(value("one"))
        clock = 5_000
        stack.push(value("one-two"))

        assertEquals("one", stack.undo(value("one-two-three"))?.text)
        assertNull(stack.undo(value("one")))
    }

    @Test
    fun `redo restores the undone branch and new edits clear it`() {
        var clock = 0L
        val stack = UndoStack(now = { clock })

        stack.push(value("a"))
        clock = 5_000
        stack.push(value("ab"))

        val undone = stack.undo(value("abc"))
        assertEquals("ab", undone?.text)
        assertTrue(stack.canRedo)

        val redone = stack.redo(value("ab"))
        assertEquals("abc", redone?.text)
        assertFalse(stack.canRedo)

        // undo again, then make a NEW edit — redo branch must vanish
        assertEquals("ab", stack.undo(value("abc"))?.text)
        clock = 20_000
        stack.push(value("ab"))
        assertFalse("new edit must clear redo", stack.canRedo)
    }

    @Test
    fun `reset clears everything`() {
        val stack = UndoStack(now = { 0L })
        stack.push(value("x"))
        stack.reset()
        assertFalse(stack.canUndo)
        assertFalse(stack.canRedo)
        assertNull(stack.undo(value("x")))
    }

    @Test
    fun `stack is bounded`() {
        var clock = 0L
        val stack = UndoStack(limit = 3, now = { clock })
        // each push 2s apart -> separate steps
        repeat(10) { i ->
            stack.push(value("v$i"))
            clock += 2_000
        }
        var current = value("final")
        var count = 0
        while (true) {
            val next = stack.undo(current) ?: break
            current = next
            count++
        }
        assertEquals(3, count)
        assertEquals("v7", current.text)
    }
}
