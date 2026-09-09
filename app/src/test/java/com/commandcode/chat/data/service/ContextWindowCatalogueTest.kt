package com.commandcode.chat.data.service

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class ContextWindowCatalogueTest {
    @Test fun bundledCapacitiesCoverExactCatalogueIdsWithoutGuessingFreeAliases() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val capacities = ContextWindowCatalogue.load(context)
        val models = ModelCatalogueRepository(context).loadLocal().models
        assertEquals(models.map { it.apiId }.toSet(), capacities.limits.keys)
        assertEquals(1_050_000L, capacities.limitFor("gpt-5.6-sol"))
        assertEquals(262_000L, capacities.limitFor("moonshotai/Kimi-K2.7-Code-Highspeed"))
        assertEquals(256_000L, capacities.limitFor("moonshotai/Kimi-K2.7-Code"))
        assertEquals(setOf("minimax/minimax-m3-free", "minimax/minimax-m2.7-free"),
            capacities.limits.filterValues { it == null }.keys)
        assertNull(capacities.limitFor("MINIMAXAI/MiniMax-M3"))
        assertNull(capacities.limitFor("unknown"))
        assertEquals("2026-09-10", capacities.verifiedAt)
        assertEquals("https://api.commandcode.ai/provider/v1/models", capacities.source)
    }

    @Test fun malformedCapacitiesAreRejectedRatherThanCoerced() {
        fun json(models: String) = """{"schemaVersion":1,"verifiedAt":"2026-09-10","source":"https://api.commandcode.ai/provider/v1/models","models":[$models]}"""
        for (value in listOf("0", "-1", "1.5", "\"100\"", "9223372036854775808", "true")) {
            assertTrue(value, runCatching { ContextWindowCatalogue.decode(json("""{"id":"a","contextWindowTokens":$value}""")) }.isFailure)
        }
        assertTrue(runCatching { ContextWindowCatalogue.decode(json("""{"id":"a","contextWindowTokens":100},{"id":"a","contextWindowTokens":200}""")) }.isFailure)
        assertTrue(runCatching { ContextWindowCatalogue.decode(" ".repeat(65_537)) }.isFailure)
        assertNull(ContextWindowCatalogue.decode(json("""{"id":"a","contextWindowTokens":null}""")).limitFor("a"))
    }
}
