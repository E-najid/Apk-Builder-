package com.enajid.apkbuilder.data.ai

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeChatApi(private val responses: MutableList<ChatResponse>) : ChatApi {
    val requests = mutableListOf<ChatRequest>()

    override suspend fun chat(request: ChatRequest): ChatResponse {
        requests += request
        check(requests.size <= responses.size) { "unexpected extra chat call #${requests.size}" }
        return responses[requests.size - 1]
    }
}

class FakeProject : AgentProjectAccess {
    val writes = mutableListOf<Pair<String, String>>()
    val files = mutableMapOf("app/build.gradle.kts" to "namespace = \"com.t\"\nminSdk = 24")

    override suspend fun listPaths(): List<String> = files.keys.toList()

    override suspend fun readFile(path: String): String? = files[path]

    override suspend fun writeFile(path: String, content: String) {
        writes += path to content
        files[path] = content
    }
}

class AiAgentTest {

    private fun toolCall(id: String, name: String, args: String) =
        ToolCall(id = id, function = FunctionCall(name = name, arguments = args))

    @Test
    fun `agent writes a file then finishes`() = runBlocking {
        val write = ChatResponse(
            choices = listOf(
                Choice(
                    message = AssistantMessage(
                        content = "Creating the file",
                        tool_calls = listOf(
                            toolCall(
                                "c1", "write_file",
                                """{"path":"app/src/main/java/com/t/Tip.kt","content":"class Tip"}"""
                            )
                        ),
                    )
                )
            )
        )
        val done = ChatResponse(
            choices = listOf(Choice(message = AssistantMessage(content = "Added Tip.kt")))
        )
        val api = FakeChatApi(mutableListOf(write, done))
        val project = FakeProject()
        val events = mutableListOf<AgentEvent>()
        val agent = AiAgent(api, project)

        val result = agent.run("m", "system", emptyList(), "make a tip calculator") { events += it }

        assertEquals("Added Tip.kt", result.finalText)
        assertEquals(listOf("app/src/main/java/com/t/Tip.kt" to "class Tip"), project.writes)
        assertEquals(2, api.requests.size)

        // The second request carries the tool result with the matching call id.
        val toolMsg = api.requests[1].messages.first { it.role == "tool" }
        assertEquals("c1", toolMsg.tool_call_id)
        assertTrue(toolMsg.content!!.contains("ok: wrote"))

        // Interim narration and tool activity surfaced as events.
        assertTrue(events.any { it is AgentEvent.TextDelta && it.text == "Creating the file" })
        assertTrue(events.any { it is AgentEvent.ToolActivity && it.label.contains("Tip.kt") })

        // The first request ends with the user message.
        assertEquals("user", api.requests[0].messages.last().role)
    }

    @Test
    fun `read errors go back to the model`() = runBlocking {
        val read = ChatResponse(
            choices = listOf(
                Choice(
                    message = AssistantMessage(
                        tool_calls = listOf(toolCall("c9", "read_file", """{"path":"nope.kt"}"""))
                    )
                )
            )
        )
        val done = ChatResponse(
            choices = listOf(Choice(message = AssistantMessage(content = "ok")))
        )
        val api = FakeChatApi(mutableListOf(read, done))
        val agent = AiAgent(api, FakeProject())

        agent.run("m", "s", emptyList(), "x") {}

        val toolMsg = api.requests[1].messages.first { it.role == "tool" }
        assertTrue(toolMsg.content!!.contains("error"))
    }

    @Test
    fun `streamed text arrives as deltas without duplication`() = runBlocking {
        val write = ChatResponse(
            choices = listOf(
                Choice(
                    message = AssistantMessage(
                        tool_calls = listOf(toolCall("c1", "read_file", """{"path":"app/build.gradle.kts"}""")
                    )
                )
            )
        )
        val final = ChatResponse(
            choices = listOf(Choice(message = AssistantMessage(content = "All done")))
        )
        var call = 0
        val api = object : ChatApi {
            override suspend fun chat(request: ChatRequest): ChatResponse =
                error("should use streaming")

            override suspend fun chatStream(
                request: ChatRequest,
                onDelta: (String) -> Unit,
            ): ChatResponse {
                call++
                return if (call == 1) write else {
                    onDelta("All ")
                    onDelta("done")
                    final
                }
            }
        }
        val events = mutableListOf<AgentEvent>()
        val result = AiAgent(api, FakeProject()).run("m", "s", emptyList(), "x") { events += it }

        assertEquals("All done", result.finalText)
        // both delta pieces surfaced live
        assertTrue(events.any { it is AgentEvent.TextDelta && it.text == "All " })
        assertTrue(events.any { it is AgentEvent.TextDelta && it.text == "done" })
        // the assembled final text is NOT re-emitted as a delta
        assertTrue(events.none { it is AgentEvent.TextDelta && it.text == "All done" })
    }

    @Test
    fun `empty model answer fails with a helpful error`() = runBlocking {
        val empty = ChatResponse(
            choices = listOf(Choice(message = AssistantMessage(content = null)))
        )
        val api = FakeChatApi(mutableListOf(empty))
        val agent = AiAgent(api, FakeProject())

        try {
            agent.run("m", "s", emptyList(), "x") {}
            throw AssertionError("expected AiException")
        } catch (e: AiException) {
            assertTrue(e.message!!, e.message!!.contains("tool"))
        }
    }

    @Test
    fun `step limit stops the loop`() = runBlocking {
        val endless = ChatResponse(
            choices = listOf(
                Choice(
                    message = AssistantMessage(
                        tool_calls = listOf(
                            toolCall("c", "read_file", """{"path":"app/build.gradle.kts"}""")
                        )
                    )
                )
            )
        )
        val api = FakeChatApi(MutableList(AiAgent.MAX_STEPS + 3) { endless })
        val agent = AiAgent(api, FakeProject())

        val result = agent.run("m", "s", emptyList(), "x") {}

        assertNull(result.finalText)
        assertEquals(AiAgent.MAX_STEPS, api.requests.size)
    }
}

class AiDtosTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `request encodes in openai chat format`() {
        val request = ChatRequest(
            model = "auto/coding",
            messages = listOf(
                ChatMessage(role = "system", content = "sys"),
                ChatMessage(
                    role = "assistant",
                    tool_calls = listOf(
                        ToolCall(id = "t1", function = FunctionCall("write_file", "{}"))
                    ),
                ),
                ChatMessage(role = "tool", content = "ok", tool_call_id = "t1"),
            ),
            tools = AiAgent(FakeChatApi(mutableListOf()), FakeProject()).tools(),
        )
        val encoded = json.encodeToString(ChatRequest.serializer(), request)

        assertTrue(encoded.contains("\"tool_call_id\":\"t1\""))
        assertTrue(encoded.contains("\"tool_calls\""))
        assertTrue(encoded.contains("\"type\":\"function\""))
        assertTrue(encoded.contains("\"write_file\""))
        assertTrue(encoded.contains("\"stream\":false"))
    }

    @Test
    fun `response decodes from an openai style payload`() {
        val payload = """
            {"id":"x","object":"chat.completion","created":1,"model":"m",
             "choices":[{"index":0,"finish_reason":"tool_calls",
               "message":{"role":"assistant","content":null,
                 "tool_calls":[{"id":"call_1","type":"function",
                   "function":{"name":"read_file","arguments":"{\"path\":\"a.kt\"}"}}]}}],
             "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
        """.trimIndent()

        val decoded = json.decodeFromString(ChatResponse.serializer(), payload)
        val call = decoded.choices[0].message!!.tool_calls!![0]

        assertEquals("call_1", call.id)
        assertEquals("read_file", call.function.name)
        assertEquals("{\"path\":\"a.kt\"}", call.function.arguments)
        assertEquals(15L, decoded.usage!!.total_tokens)
    }
}
