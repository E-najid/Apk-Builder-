package com.enajid.apkbuilder.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class McpProtocolTest {

    @Test
    fun `requests are valid json-rpc with sequential shape`() {
        val init = McpProtocol.initializeRequest(1)
        assertTrue(init.contains(""""method":"initialize""""))
        assertTrue(init.contains(""""jsonrpc":"2.0""""))
        assertTrue(init.contains(McpProtocol.PROTOCOL_VERSION))

        val list = McpProtocol.listToolsRequest(2)
        assertTrue(list.contains(""""method":"tools/list""""))

        val call = McpProtocol.callToolRequest(3, "weather", """{"city":"Khulna"}""")
        assertTrue(call.contains(""""method":"tools/call""""))
        assertTrue(call.contains(""""name":"weather""""))
        assertTrue(call.contains(""""city":"Khulna""""))

        val badArgs = McpProtocol.callToolRequest(4, "weather", "not json")
        assertTrue(badArgs.contains(""""arguments":{}"""))
    }

    @Test
    fun `parse response matches id and surfaces errors`() {
        val ok = McpProtocol.parseResponse(
            """{"jsonrpc":"2.0","id":7,"result":{"tools":[]}}""",
            7,
        )
        assertTrue(ok is McpProtocol.RpcResponse.Ok)

        val wrongId = McpProtocol.parseResponse(
            """{"jsonrpc":"2.0","id":8,"result":{}}""",
            7,
        )
        assertTrue(wrongId is McpProtocol.RpcResponse.NotOurs)

        val notification = McpProtocol.parseResponse(
            """{"jsonrpc":"2.0","method":"notifications/message","params":{}}""",
            7,
        )
        assertTrue(notification is McpProtocol.RpcResponse.NotOurs)

        val err = McpProtocol.parseResponse(
            """{"jsonrpc":"2.0","id":7,"error":{"code":-32000,"message":"tool not found"}}""",
            7,
        ) as McpProtocol.RpcResponse.Err
        assertEquals("tool not found", err.message)
    }

    @Test
    fun `sse bodies are scanned for our response`() {
        val body = """
            event: message
            data: {"jsonrpc":"2.0","method":"notifications/progress","params":{"p":1}}

            event: message
            data: {"jsonrpc":"2.0","id":5,"result":{"content":[{"type":"text","text":"hello"}]}}

        """.trimIndent()
        val found = McpProtocol.findResponse(body, 5)
        assertTrue(found is McpProtocol.RpcResponse.Ok)

        val none = McpProtocol.findResponse("""{"jsonrpc":"2.0","id":5,"result":null}""", 6)
        assertTrue(none is McpProtocol.RpcResponse.Err)
    }

    @Test
    fun `tool lists parse with pagination cursor`() {
        val json = """
            {"tools": [
              {"name": "weather", "description": "Get weather", "inputSchema": {"type": "object"}},
              {"name": "no_schema"}
            ], "nextCursor": "page2"}
        """.trimIndent()
        val (tools, cursor) = McpProtocol.parseToolList(
            kotlinx.serialization.json.Json.parseToJsonElement(json),
        )
        assertEquals(2, tools.size)
        assertEquals("weather", tools[0].name)
        assertEquals("Get weather", tools[0].description)
        assertEquals(
            """{"type":"object"}""",
            tools[0].inputSchema.toString().replace(" ", ""),
        )
        assertEquals("no_schema", tools[1].name) // kept with an empty schema
        assertTrue(tools[1].inputSchema.isEmpty())
        assertEquals("page2", cursor)
    }

    @Test
    fun `tool call results join text parts and flag errors`() {
        fun parse(text: String) = McpProtocol.parseToolCallResult(
            kotlinx.serialization.json.Json.parseToJsonElement(text),
        )
        val (text, isError) = parse(
            """{"content": [{"type": "text", "text": "line1"}, {"type": "text", "text": "line2"}]}""",
        )
        assertEquals("line1\nline2", text)
        assertEquals(false, isError)

        val (errText, errFlag) = parse(
            """{"content": [{"type": "text", "text": "boom"}], "isError": true}""",
        )
        assertEquals("boom", errText)
        assertEquals(true, errFlag)
    }

    @Test
    fun `slug and prefix round trip`() {
        assertEquals("mytools", McpProtocol.slugFromName("My Tools!", 1))
        assertEquals("srv7", McpProtocol.slugFromName("!!!", 7))
        assertEquals("cafe2024", McpProtocol.slugFromName("Café 2024", 2))

        val prefixed = McpProtocol.prefixedToolName("mytools", "weather")
        assertEquals("mcp__mytools__weather", prefixed)
        assertEquals("mytools" to "weather", McpProtocol.splitPrefixed(prefixed))
        assertNull(McpProtocol.splitPrefixed("read_file"))
        assertNull(McpProtocol.splitPrefixed("mcp__weather"))
    }
}
