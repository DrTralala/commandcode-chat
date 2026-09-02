package com.commandcode.chat.data.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CommandCodePlanTest {
    @Test
    fun knownPlanIdsResolveToTheirDisplayNamesAndMonthlyCaps() {
        assertEquals(CommandCodePlan("Go", 10.0), commandCodePlan("individual-go"))
        assertEquals(CommandCodePlan("GOAT", 70.0), commandCodePlan("individual-goat"))
        assertEquals(CommandCodePlan("Pro", 30.0), commandCodePlan("individual-pro"))
        assertEquals(CommandCodePlan("Pro", 80.0), commandCodePlan("individual-pro-v1"))
        assertEquals(CommandCodePlan("Provider", 15.0), commandCodePlan("individual-provider"))
        assertEquals(CommandCodePlan("Max 10×", 150.0), commandCodePlan("individual-max"))
        assertEquals(CommandCodePlan("Max 20×", 300.0), commandCodePlan("individual-ultra"))
        assertEquals(CommandCodePlan("Team Pro", 40.0), commandCodePlan("teams-pro"))
    }

    @Test
    fun missingAndUnknownPlanIdsRemainUnknown() {
        assertNull(commandCodePlan(null))
        assertNull(commandCodePlan("future-plan"))
    }
}
