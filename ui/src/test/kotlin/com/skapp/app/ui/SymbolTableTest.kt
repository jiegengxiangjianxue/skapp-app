package com.skapp.app.ui

import com.skapp.app.data.SymbolEntry
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class SymbolTableTest {
    @Test
    fun partialNetworkTableCannotReplaceFullSnapshot() {
        val partial = listOf(SymbolEntry("000001", "测试"))
        assertThrows(IllegalArgumentException::class.java) {
            SkappViewModel.completeSymbolTable(partial)
        }
    }

    @Test
    fun completeTableKeepsItsVerifiedEntries() {
        val complete = List(3000) { SymbolEntry("%06d".format(it), null) }
        assertSame(complete, SkappViewModel.completeSymbolTable(complete))
    }
}
