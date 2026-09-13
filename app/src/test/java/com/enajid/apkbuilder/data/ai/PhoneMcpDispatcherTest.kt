package com.enajid.apkbuilder.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMcpDispatcherTest {

    private fun dispatcher(
        calls: MutableList<Pair<String, String>> = mutableListOf(),
        handler: suspend (String, String) -> Pair<String, Boolean> = { _, _ -> "ok" to false },
    ) = PhoneMcpDispatcher(
        serverName = "test",
        tools = listOf(
            PhoneMcpTool(
                name = "echo",
                description = "echoes text",
                inputSchema = buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject { })
                },
            ),
        ),
        callTool = { name, args ->
            calls += name to args
            handler(name, args)
        },
    )

    private fun bodyOf(result: McpDispatchResult): kotlinx.serialization.json.JsonObject =
        Json.parseToJsonElement(result.body!!).jsonObject

    @Test
    fun `initialize answers protocol version capabilities and session id`() = runBlocking {
        val result = dispatcher().handle(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}""",
        )
        assertEquals(200, result.status)
        assertTrue(result.sessionId != null)
        val body = bodyOf(result)
        assertEquals("2025-06-18", body["result"]!!.jsonObject["protocolVersion"]!!.jsonPrimitive.content)
        assertEquals("test", body["result"]!!.jsonObject["serverInfo"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue("tools" in body["result"]!!.jsonObject["capabilities"]!!.jsonObject)
    }

    @Test
    fun `initialized notification gets 202 with no body`() = runBlocking {
        val result = dispatcher().handle(
            """{"jsonrpc":"2.0","method":"notifications/initialized"}""",
        )
        assertEquals(202, result.status)
        assertNull(result.body)
    }

    @Test
    fun `tools list exposes declared tools`() = runBlocking {
        val result = dispatcher().handle("""{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")
        val tools = bodyOf(result)["result"]!!.jsonObject["tools"]!!.jsonArray
        assertEquals(1, tools.size)
        assertEquals("echo", tools[0].jsonObject["name"]!!.jsonPrimitive.content)
        assertTrue("inputSchema" in tools[0].jsonObject)
    }

    @Test
    fun `tools call passes arguments and returns content`() = runBlocking {
        val calls = mutableListOf<Pair<String, String>>()
        val dispatcher = dispatcher(calls) { _, _ -> "hello world" to false }
        val result = dispatcher.handle(
            """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"echo","arguments":{"text":"hi"}}}""",
        )
        assertEquals(listOf("echo" to """{"text":"hi"}"""), calls)
        val content = bodyOf(result)["result"]!!.jsonObject["content"]!!.jsonArray[0].jsonObject
        assertEquals("text", content["type"]!!.jsonPrimitive.content)
        assertEquals("hello world", content["text"]!!.jsonPrimitive.content)
        assertFalse(bodyOf(result)["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `tools call error flag is forwarded`() = runBlocking {
        val dispatcher = dispatcher { _, _ -> "boom" to true }
        val result = dispatcher.handle(
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"echo"}}""",
        )
        assertTrue(bodyOf(result)["result"]!!.jsonObject["isError"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `unknown tool and method give json-rpc errors`() = runBlocking {
        val unknownTool = dispatcher().handle(
            """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"nope"}}""",
        )
        assertEquals(-32602, bodyOf(unknownTool)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())

        val unknownMethod = dispatcher().handle(
            """{"jsonrpc":"2.0","id":6,"method":"resources/list"}""",
        )
        assertEquals(-32601, bodyOf(unknownMethod)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `invalid json gives parse error`() = runBlocking {
        val result = dispatcher().handle("this is not json")
        assertEquals(-32700, bodyOf(result)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `ping returns empty result`() = runBlocking {
        val result = dispatcher().handle("""{"jsonrpc":"2.0","id":9,"method":"ping"}""")
        assertEquals(200, result.status)
        assertTrue(bodyOf(result)["result"]!!.jsonObject.isEmpty())
    }
}
