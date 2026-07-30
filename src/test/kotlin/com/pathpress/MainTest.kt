package com.pathpress

import com.github.ajalt.clikt.core.parse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MainTest {

    private class TestPathPressCommand : PathPressCommand() {
        override fun run() {
            // No-op for option parsing unit tests (avoids loading OSM PBF data)
        }
    }

    @Test
    fun `llmModel defaults to null when --llm-model option is not passed`() {
        val command = TestPathPressCommand()
        command.parse(listOf("--start", "SF", "--end", "LA"))
        assertNull(command.llmModel)
    }

    @Test
    fun `llmModel is populated when --llm-model option is passed`() {
        val command = TestPathPressCommand()
        command.parse(listOf("--start", "SF", "--end", "LA", "--llm-model", "custom-model"))
        assertEquals("custom-model", command.llmModel)
    }
}
