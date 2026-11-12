package com.commandcode.chat.data.service

import com.commandcode.chat.domain.ApiFamily
import com.commandcode.chat.domain.ChatModel
import java.time.Instant
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CommandCodeModelsResponseCodecTest {
    @Test
    fun endpointMetadataSelectsProtocolWithoutSol() {
        val result = CommandCodeModelsResponseCodec.decode(
            """{"object":"list","origin":"BUNDLED","data":[
              {"id":"vendor/chat-new","name":"New chat","context_length":131072,"supported_endpoints":["/chat/completions","/messages"]},
              {"id":"vendor/messages-new","name":"New messages","context_length":200000,"supported_endpoints":["/messages"]},
              {"id":"vendor/other","name":"Other","supported_endpoints":["/responses"]}
            ]}""",
            FETCHED_AT,
        )

        assertEquals(
            listOf(ApiFamily.OPENAI_CHAT, ApiFamily.ANTHROPIC_MESSAGES),
            result.models.map(ChatModel::apiFamily),
        )
        assertEquals(listOf("vendor/chat-new", "vendor/messages-new"), result.models.map(ChatModel::apiId))
        assertEquals(200000L, result.models.last().contextWindowTokens)
        assertEquals(2, result.schemaVersion)
        assertEquals(FETCHED_AT.toEpochMilli(), result.generatedAt)
        assertEquals("live-${FETCHED_AT.toEpochMilli()}", result.catalogueVersion)
        assertEquals(CatalogueOrigin.LIVE, result.origin)
    }

    @Test
    fun missingAndNullContextLengthsAreUnknown() {
        val result = CommandCodeModelsResponseCodec.decode(
            """{"data":[
              {"id":"vendor/missing","name":"Missing","supported_endpoints":["/messages"]},
              {"id":"vendor/null","name":"Null","context_length":null,"supported_endpoints":["/chat/completions"]}
            ]}""",
            FETCHED_AT,
        )

        assertEquals(listOf(null, null), result.models.map(ChatModel::contextWindowTokens))
    }

    @Test
    fun rejectsInvalidContextLengths() {
        listOf("1.5", "0", "-1", "9223372036854775808", "\"131072\"", "true").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) {
                CommandCodeModelsResponseCodec.decode("""{"data":[${model(context = value)}]}""", FETCHED_AT)
            }
        }
    }

    @Test
    fun rejectsDuplicateIdsEvenWhenOneEntryIsUnsupported() {
        val text = """{"data":[
          ${model(id = "vendor/duplicate", endpoints = "[\"/responses\"]")},
          ${model(id = "vendor/duplicate", endpoints = "[\"/messages\"]")}
        ]}"""

        assertThrows(IllegalArgumentException::class.java) {
            CommandCodeModelsResponseCodec.decode(text, FETCHED_AT)
        }
    }

    @Test
    fun rejectsBlankPaddedAndControlContainingIdsOrNames() {
        listOf(
            model(id = ""),
            model(id = "   "),
            model(id = " vendor/model"),
            model(id = "vendor/model "),
            model(id = "vendor/\u0001model"),
            model(name = ""),
            model(name = "   "),
            model(name = "Bad\u007fName"),
        ).forEach { row ->
            assertThrows(IllegalArgumentException::class.java) {
                CommandCodeModelsResponseCodec.decode("""{"data":[$row]}""", FETCHED_AT)
            }
        }
    }

    @Test
    fun rejectsOversizedIdsAndDisplayNames() {
        val tooLongId = model(id = "i".repeat(257))
        val tooLongName = model(name = "n".repeat(201))

        assertThrows(IllegalArgumentException::class.java) {
            CommandCodeModelsResponseCodec.decode("""{"data":[$tooLongId]}""", FETCHED_AT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CommandCodeModelsResponseCodec.decode("""{"data":[$tooLongName]}""", FETCHED_AT)
        }
    }

    @Test
    fun acceptsIdsAndDisplayNamesAtTheirCharacterLimits() {
        val id = "i".repeat(256)
        val name = "n".repeat(200)
        val result = CommandCodeModelsResponseCodec.decode(
            """{"data":[${model(id = id, name = name)}]}""",
            FETCHED_AT,
        )

        assertEquals(id, result.models.single().apiId)
        assertEquals(name, result.models.single().displayName)
    }

    @Test
    fun rejectsMalformedEndpointArraysAndEmptyUsableLists() {
        listOf("null", "\"/messages\"", "[\"/messages\",1]").forEach { endpoints ->
            assertThrows(IllegalArgumentException::class.java) {
                CommandCodeModelsResponseCodec.decode(
                    """{"data":[${model(endpoints = endpoints)}]}""",
                    FETCHED_AT,
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            CommandCodeModelsResponseCodec.decode(
                """{"data":[${model(endpoints = "[\"/responses\"]")}]}""",
                FETCHED_AT,
            )
        }
    }

    @Test
    fun rejectsTrailingJsonOversizedInputAndTooManyRawModels() {
        val valid = """{"data":[${model()}]}"""
        assertThrows(IllegalArgumentException::class.java) {
            CommandCodeModelsResponseCodec.decode("$valid {}", FETCHED_AT)
        }
        assertThrows(IllegalArgumentException::class.java) {
            CommandCodeModelsResponseCodec.decode(" ".repeat(1_048_577), FETCHED_AT)
        }

        val tooMany = (0..1_000).joinToString(",") { index -> model(id = "vendor/$index") }
        assertThrows(IllegalArgumentException::class.java) {
            CommandCodeModelsResponseCodec.decode("""{"data":[$tooMany]}""", FETCHED_AT)
        }
    }

    @Test
    fun allowsUnknownApiFieldsAndIgnoresUnknownEndpointValues() {
        val result = CommandCodeModelsResponseCodec.decode(
            """{"data":[{"id":"vendor/chat","name":"Chat","apiFamily":"anthropic-messages","supported_endpoints":["/future","/chat/completions"],"future_field":{"enabled":true}}],"future_envelope_field":7}""",
            FETCHED_AT,
        )

        assertEquals(1, result.models.size)
        assertEquals(ApiFamily.OPENAI_CHAT, result.models.single().apiFamily)
        assertEquals("vendor/chat", result.models.single().apiId)
    }

    @Test
    fun rejectsDuplicateJsonProperties() {
        val row = model()
        val duplicated = """{"data":[$row],"data":[$row]}"""

        assertThrows(IllegalArgumentException::class.java) {
            CommandCodeModelsResponseCodec.decode(duplicated, FETCHED_AT)
        }
    }

    @Test
    fun rejectsMissingOrCoercedRequiredFields() {
        listOf(
            """{"data":[{"name":"Missing id","supported_endpoints":["/messages"]}]}""",
            """{"data":[{"id":12,"name":"Not a string","supported_endpoints":["/messages"]}]}""",
            """{"data":[{"id":"vendor/missing-endpoints","name":"Missing endpoints"}]}""",
            """{"data":"not an array"}""",
        ).forEach { text ->
            assertThrows(IllegalArgumentException::class.java) {
                CommandCodeModelsResponseCodec.decode(text, FETCHED_AT)
            }
        }
    }

    private fun model(
        id: String = "vendor/model",
        name: String = "Model",
        endpoints: String = "[\"/messages\"]",
        context: String? = null,
    ): String = buildString {
        append("{\"id\":${JSONObject.quote(id)},\"name\":${JSONObject.quote(name)},\"supported_endpoints\":$endpoints")
        context?.let { append(",\"context_length\":$it") }
        append('}')
    }

    companion object {
        private val FETCHED_AT = Instant.parse("2026-11-04T00:00:00Z")
    }
}
