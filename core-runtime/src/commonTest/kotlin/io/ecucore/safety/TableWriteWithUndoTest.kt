package io.ecucore.safety

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TableWriteWithUndoTest {
    private val key = UndoKey(UndoTableKind.VE, 1)

    /** ECU fingida: guarda o valor "gravado" e permite falhar leitura/gravação. */
    private class FakeEcu(var value: String) {
        var failRead = false
        var failWrite = false
        val events = mutableListOf<String>()
        suspend fun read(): String { events += "read"; if (failRead) error("read"); return value }
        suspend fun write(v: String) { events += "write:$v"; if (failWrite) error("write"); value = v }
    }

    @Test
    fun edit_records_previous_read_from_ecu_not_screen() = runTest {
        val store = TableUndoStore()
        val ecu = FakeEcu("ecu")
        val out = TableWriteWithUndo(store).write(key, "new", TableWriteMode.EDIT, ecu::read, ecu::write)
        assertIs<TableWriteOutcome.Written<String>>(out)
        assertEquals("ecu", out.previous)
        assertTrue(out.undoRecorded)
        assertEquals("ecu", store.peek<String>(key))
        assertEquals(listOf("read", "write:new"), ecu.events)
    }

    @Test
    fun before_write_runs_first() = runTest {
        val ecu = FakeEcu("a")
        val uc = TableWriteWithUndo(TableUndoStore(), beforeWrite = { ecu.events += "backup" })
        uc.write(key, "b", TableWriteMode.EDIT, ecu::read, ecu::write)
        assertEquals(listOf("backup", "read", "write:b"), ecu.events)
    }

    @Test
    fun failed_write_leaves_stack_untouched() = runTest {
        val store = TableUndoStore()
        val ecu = FakeEcu("a").apply { failWrite = true }
        val out = TableWriteWithUndo(store).write(key, "b", TableWriteMode.EDIT, ecu::read, ecu::write)
        assertIs<TableWriteOutcome.Failed>(out)
        assertTrue(store.available.value.isEmpty())
    }

    @Test
    fun failed_before_write_aborts_write() = runTest {
        val ecu = FakeEcu("a")
        val out = TableWriteWithUndo(TableUndoStore(), beforeWrite = { error("backup") })
            .write(key, "b", TableWriteMode.EDIT, ecu::read, ecu::write)
        assertIs<TableWriteOutcome.Failed>(out)
        assertEquals("a", ecu.value)
    }

    @Test
    fun unreadable_previous_writes_without_undo() = runTest {
        val store = TableUndoStore()
        val ecu = FakeEcu("a").apply { failRead = true }
        var reported: UndoKey? = null
        val out = TableWriteWithUndo(store, onPreviousUnreadable = { k, _ -> reported = k })
            .write(key, "b", TableWriteMode.EDIT, ecu::read, ecu::write)
        assertIs<TableWriteOutcome.Written<String>>(out)
        assertNull(out.previous)
        assertFalse(out.undoRecorded)
        assertEquals(key, reported)
        assertEquals("b", ecu.value)
        assertTrue(store.available.value.isEmpty())
    }

    @Test
    fun identical_write_records_nothing() = runTest {
        val store = TableUndoStore()
        val ecu = FakeEcu("a")
        val out = TableWriteWithUndo(store).write(key, "a", TableWriteMode.EDIT, ecu::read, ecu::write)
        assertIs<TableWriteOutcome.Written<String>>(out)
        assertFalse(out.undoRecorded)
        assertTrue(store.available.value.isEmpty())
    }

    @Test
    fun undo_mode_skips_read_and_pops_only_on_success() = runTest {
        val store = TableUndoStore().apply { push(key, "old1"); push(key, "old2") }
        val ecu = FakeEcu("cur").apply { failWrite = true }
        val uc = TableWriteWithUndo(store)
        uc.write(key, "old2", TableWriteMode.UNDO, ecu::read, ecu::write)
        assertEquals("old2", store.peek<String>(key))
        ecu.failWrite = false
        uc.write(key, "old2", TableWriteMode.UNDO, ecu::read, ecu::write)
        assertEquals("old1", store.peek<String>(key))
        assertFalse("read" in ecu.events)
    }
}
