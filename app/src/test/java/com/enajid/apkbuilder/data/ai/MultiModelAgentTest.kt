package com.enajid.apkbuilder.data.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class ScriptedApi(private val responses: MutableList<ChatResponse>) : ChatApi {
    val requests = mutableListOf<ChatRequest>()
    var failWith: Exception? = null

    override suspend fun chat(request: ChatRequest): ChatResponse {
        requests += request
        failWith?.let { throw it }
        check(requests.size <= responses.size) { "unexpected chat call #${requests.size}" }
        return responses[requests.size - 1]
    }
}

private class FakeProject : AgentProjectAccess {
    val files = mutableMapOf("app/build.gradle.kts" to "minSdk = 24")

    override suspend fun listPaths(): List<String> = files.keys.toList()

    override suspend fun readFile(path: String): String? = files[path]

    override suspend fun writeFile(path: String, content: String) {
        files[path] = content
    }
}

private fun toolCall(id: String, name: String, args: String) =
    ToolCall(id = id, function = FunctionCall(name = name, arguments = args))

private fun writeResponse(path: String): ChatResponse = ChatResponse(
    choices = listOf(
        Choice(
            message = AssistantMessage(
                tool_calls = listOf(
                    toolCall("c1", "write_file", """{"path":"$path","content":"class New"}""")
                )
            )
        )
    )
)

private fun textResponse(text: String): ChatResponse =
    ChatResponse(choices = listOf(Choice(message = AssistantMessage(content = text))))

class MultiModelAgentTest {

    @Test
    fun `reviewer approves and no fix round happens`() = runBlocking {
        val coder = ScriptedApi(mutableListOf(writeResponse("app/src/main/java/A.kt"), textResponse("Added A.kt")))
        val reviewer = ScriptedApi(mutableListOf(textResponse("OK")))
        val agent = MultiModelAgent(chat = coder, reviewer = reviewer, reviewerModel = "review-model")

        val result = agent.run("coder-model", "sys", emptyList(), "add a class", FakeProject()) {}

        assertEquals("Added A.kt", result.finalText)
        // coder: write round + final answer; no fix round
        assertEquals(2, coder.requests.size)
        // reviewer: single call, reviewing the written file with its model id
        assertEquals(1, reviewer.requests.size)
        assertEquals("review-model", reviewer.requests[0].model)
        val reviewUser = reviewer.requests[0].messages.last()
        assertTrue(reviewUser.content!!.contains("app/src/main/java/A.kt"))
        assertTrue(reviewUser.content!!.contains("class New"))
    }

    @Test
    fun `reviewer issues trigger a coder fix round`() = runBlocking {
        val coder = ScriptedApi(
            mutableListOf(
                writeResponse("app/src/main/java/A.kt"),
                textResponse("Added A.kt"),
                textResponse("Fixed the import"),
            )
        )
        val reviewer = ScriptedApi(mutableListOf(textResponse("1. A.kt: missing package declaration")))
        val agent = MultiModelAgent(chat = coder, reviewer = reviewer, reviewerModel = "r")

        val result = agent.run("c", "sys", emptyList(), "add a class", FakeProject()) {}

        assertEquals("Fixed the import", result.finalText)
        assertEquals(3, coder.requests.size)
        val fixRequest = coder.requests[2]
        val lastMessage = fixRequest.messages.last()
        assertEquals("user", lastMessage.role)
        assertTrue(lastMessage.content!!.contains("Code review feedback"))
        assertTrue(lastMessage.content!!.contains("missing package declaration"))
        // the fix round remembers the original conversation
        assertTrue(fixRequest.messages.any { it.content == "add a class" })
    }

    @Test
    fun `broken reviewer never loses the coder work`() = runBlocking {
        val coder = ScriptedApi(mutableListOf(writeResponse("A.kt"), textResponse("done")))
        val reviewer = ScriptedApi(mutableListOf())
        reviewer.failWith = AiException("reviewer provider down")
        val agent = MultiModelAgent(chat = coder, reviewer = reviewer, reviewerModel = "r")

        val result = agent.run("c", "s", emptyList(), "x", FakeProject()) {}

        assertEquals("done", result.finalText)
        assertEquals(2, coder.requests.size)
    }

    @Test
    fun `no reviewer means single agent run`() = runBlocking {
        val coder = ScriptedApi(mutableListOf(writeResponse("A.kt"), textResponse("done")))
        val agent = MultiModelAgent(chat = coder, reviewer = null, reviewerModel = null)

        val result = agent.run("c", "s", emptyList(), "x", FakeProject()) {}

        assertEquals("done", result.finalText)
        assertEquals(2, coder.requests.size)
    }
}

class FallbackChatApiTest {

    @Test
    fun `falls through to the next provider and sticks with it`() = runBlocking {
        val broken = ScriptedApi(mutableListOf()).apply { failWith = AiException("quota over") }
        val working = ScriptedApi(mutableListOf(textResponse("from second"), textResponse("again second")))
        val api = FallbackChatApi(listOf(broken, working))

        val first = api.chat(ChatRequest(model = "m", messages = emptyList()))
        assertEquals("from second", first.choices[0].message!!.content)

        val second = api.chat(ChatRequest(model = "m", messages = emptyList()))
        assertEquals("again second", second.choices[0].message!!.content)

        // sticky: the broken provider was tried once, never again
        assertEquals(1, broken.requests.size)
        assertEquals(2, working.requests.size)
    }

    @Test
    fun `all providers failing rethrows the last error`() = runBlocking {
        val a = ScriptedApi(mutableListOf()).apply { failWith = AiException("a down") }
        val b = ScriptedApi(mutableListOf()).apply { failWith = AiException("b down") }
        val api = FallbackChatApi(listOf(a, b))

        try {
            api.chat(ChatRequest(model = "m", messages = emptyList()))
            throw AssertionError("expected failure")
        } catch (e: AiException) {
            assertEquals("b down", e.message)
        }
    }
}

class ProviderPresetsTest {

    @Test
    fun `unknown preset falls back to custom`() {
        assertEquals(ProviderPresets.CUSTOM, ProviderPresets.byId("nope"))
    }

    @Test
    fun `all real presets use https`() {
        ProviderPresets.all
            .filter { it.id != ProviderPresets.CUSTOM.id }
            .forEach { assertTrue(it.baseUrl, it.baseUrl.startsWith("https://")) }
    }
}
