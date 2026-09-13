package com.enajid.apkbuilder.data.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AnthropicWireTest {

    private fun request(vararg messages: ChatMessage, tools: List<ToolSpec>? = null) =
        ChatRequest(model = "claude-test", messages = messages.toList(), tools = tools)

    private fun spec(name: String) = ToolSpec(
        function = FunctionSpec(
            name = name,
            description = "does $name",
            parameters = Json.parseToJsonElement("""{"type":"object"}""").jsonObject,
        ),
    )

    @Test
    fun `system messages go to the system field and tools become input_schema`() {
        val body = AnthropicWire.buildBody(
            request(
                ChatMessage(role = "system", content = "be helpful"),
                ChatMessage(role = "user", content = "hi"),
            ),
            tools = listOf(spec("read_file")),
        )

        assertEquals("be helpful", body["system"]!!.jsonPrimitive.content)
        assertEquals("claude-test", body["model"]!!.jsonPrimitive.content)
        assertEquals(8192, body["max_tokens"]!!.jsonPrimitive.content.toInt())
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("hi", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
        val tool = body["tools"]!!.jsonArray[0].jsonObject
        assertEquals("read_file", tool["name"]!!.jsonPrimitive.content)
        assertEquals("does read_file", tool["description"]!!.jsonPrimitive.content)
        assertEquals(
            """{"type":"object"}""",
            tool["input_schema"].toString().replace(" ", ""),
        )
    }

    @Test
    fun `assistant tool calls become tool_use blocks with parsed input`() {
        val body = AnthropicWire.buildBody(
            request(
                ChatMessage(role = "system", content = "s"),
                ChatMessage(role = "user", content = "do it"),
                ChatMessage(
                    role = "assistant",
                    tool_calls = listOf(
                        ToolCall(
                            id = "t1",
                            function = FunctionCall(name = "write_file", arguments = """{"path":"A.kt"}"""),
                        ),
                    ),
                ),
                ChatMessage(role = "tool", content = "ok", tool_call_id = "t1"),
                ChatMessage(role = "assistant", content = "done"),
            ),
        )

        val messages = body["messages"]!!.jsonArray
        assertEquals(3, messages.size) // user, assistant(tool_use), user(tool_result), assistant
        val assistant = messages[1].jsonObject
        assertEquals("assistant", assistant["role"]!!.jsonPrimitive.content)
        val block = assistant["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_use", block["type"]!!.jsonPrimitive.content)
        assertEquals("t1", block["id"]!!.jsonPrimitive.content)
        assertEquals("write_file", block["name"]!!.jsonPrimitive.content)
        assertEquals("""{"path":"A.kt"}""", block["input"].toString().replace(" ", ""))
        val result = messages[2].jsonObject
        assertEquals("user", result["role"]!!.jsonPrimitive.content)
        val resultBlock = result["content"]!!.jsonArray[0].jsonObject
        assertEquals("tool_result", resultBlock["type"]!!.jsonPrimitive.content)
        assertEquals("t1", resultBlock["tool_use_id"]!!.jsonPrimitive.content)
        assertEquals("ok", resultBlock["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `consecutive user texts merge to keep roles alternating`() {
        val body = AnthropicWire.buildBody(
            request(
                ChatMessage(role = "user", content = "one"),
                ChatMessage(role = "user", content = "two"),
            ),
        )
        val messages = body["messages"]!!.jsonArray
        assertEquals(1, messages.size)
        assertEquals("one\n\ntwo", messages[0].jsonObject["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `response with text and tool_use maps back to our shapes`() {
        val response = AnthropicWire.parseResponse(
            """
            {
              "content": [
                {"type": "text", "text": "Let me check."},
                {"type": "tool_use", "id": "t9", "name": "read_file", "input": {"path": "B.kt"}}
              ],
              "stop_reason": "tool_use",
              "usage": {"input_tokens": 10, "output_tokens": 5}
            }
            """.trimIndent(),
        )
        val message = response.choices.single().message!!
        assertEquals("Let me check.", message.content)
        assertEquals("read_file", message.tool_calls!!.single().function.name)
        assertEquals("t9", message.tool_calls!!.single().id)
        assertEquals(
            """{"path":"B.kt"}""",
            message.tool_calls!!.single().function.arguments.replace(" ", ""),
        )
        assertEquals("tool_calls", response.choices.single().finish_reason)
        assertEquals(10L, response.usage?.prompt_tokens)
        assertEquals(5L, response.usage?.completion_tokens)
    }

    @Test
    fun `plain text response maps with stop finish reason`() {
        val response = AnthropicWire.parseResponse(
            """{"content": [{"type": "text", "text": "All done"}], "stop_reason": "end_turn"}""",
        )
        assertEquals("All done", response.choices.single().message!!.content)
        assertNull(response.choices.single().message!!.tool_calls)
        assertEquals("stop", response.choices.single().finish_reason)
    }

    @Test
    fun `stream accumulator assembles text deltas and tool input fragments`() {
        val acc = AnthropicWire.StreamAccumulator {}
        listOf(
            """{"type":"message_start","message":{"usage":{"input_tokens":7}}}""",
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t1","name":"search_files"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"pa"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"ttern\":\"x\"}"}}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Think"}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"ing…"}}""",
            """{"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":9}}""",
        ).forEach { acc.onPayload(it) }

        val seen = StringBuilder()
        val acc2 = AnthropicWire.StreamAccumulator { seen.append(it) }
        acc2.onPayload("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hi "}}""")
        acc2.onPayload("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"there"}}""")
        assertEquals("Hi there", seen.toString())
        assertEquals("Hi there", acc2.build().choices.single().message!!.content)

        val built = acc.build()
        val message = built.choices.single().message!!
        assertEquals("Thinking…", message.content)
        val call = message.tool_calls!!.single()
        assertEquals("search_files", call.function.name)
        assertEquals("t1", call.id)
        assertEquals("""{"pattern":"x"}""", call.function.arguments.replace(" ", ""))
        assertEquals("tool_calls", built.choices.single().finish_reason)
        assertEquals(7L, built.usage?.prompt_tokens)
        assertEquals(9L, built.usage?.completion_tokens)
    }
}
