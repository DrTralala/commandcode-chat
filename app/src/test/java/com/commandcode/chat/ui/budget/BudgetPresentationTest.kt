package com.commandcode.chat.ui.budget

import com.commandcode.chat.data.budget.BudgetWindow
import com.commandcode.chat.domain.ChatModel
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Test

class BudgetPresentationTest {
    @Test
    fun solEquivalentRemainingUsesOneToOneMultiplierAndClampsAtZero() {
        val available = BudgetWindow(BigDecimal("4"), BigDecimal("14"), null, null)
        val exhausted = BudgetWindow(BigDecimal("16"), BigDecimal("14"), null, null)

        assertEquals(0, BigDecimal("10").compareTo(equivalentRemaining(available, ChatModel.SOL)))
        assertEquals(0, BigDecimal.ZERO.compareTo(equivalentRemaining(exhausted, ChatModel.SOL)))
    }

    @Test
    fun lunaEquivalentRemainingDividesByTriplePointFiveMultiplier() {
        val window = BudgetWindow(BigDecimal.ZERO, BigDecimal("14"), null, null)

        assertEquals(0, BigDecimal("4").compareTo(equivalentRemaining(window, ChatModel.LUNA)))
    }
}
