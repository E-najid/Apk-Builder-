package com.enajid.apkbuilder.data.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Project fake with the optional tool capabilities enabled. */
private class ToolProject : AgentProjectAccess {
    val files = mutableMapOf(
        "app/src/main/java/com/t/A.kt" to "class A {\n    val greeting = \"hello\"\n}\n",
        "README.md" to "# hello world\n",
    )
    val deletes = mutableListOf<String>()
    var appliedConfig: Pair<String?, String?>? = null
    var configError: String? = null

    override suspend fun listPaths(): List<String> = files.keys.toList()

    override suspend fun readFile(path: String): String? = files[path]

    override suspend fun writeFile(path: String, content: String) {
        files[path] = content
    }

    override suspend fun deleteFile(path: String): String? {
        if (path !in files) return "error: file not found: $path"
        deletes += path
        return null
    }

    override suspend fun readAppConfig(): AppConfigSnapshot =
        AppConfigSnapshot(appName = "Test App", applicationId = "com.t.app")

    override suspend fun applyAppConfig(appName: String?, applicationId: String?): String? {
        appliedConfig = appName to applicationId
        return configError
    }

    override suspend fun buildStatus(): String =
        "run #3 \"Build APK\" — completed: success"

    override suspend fun gitLog(limit: Int): List<String> =
        listOf("abc1234 initial commit", "def5678 save changes")
}

class AgentToolsTest {

    private fun toolCall(id: String, name: String, args: String) =
        ToolCall(id = id, function = FunctionCall(name = name, arguments = args))

    /** Runs one scripted tool call, returns the api and the project used. */
    private fun runWithCall(project: ToolProject, vararg calls: ToolCall): FakeChatApi {
        val api = FakeChatApi(
            mutableListOf(
                ChatResponse(
                    choices = listOf(
                        Choice(message = AssistantMessage(content = "working", tool_calls = calls.toList()))
                    )
                ),
                ChatResponse(choices = listOf(Choice(message = AssistantMessage(content = "done")))),
            )
        )
        runBlocking { AiAgent(api, project).run("m", "s", emptyList(), "x") {} }
        return api
    }

    private fun toolResults(api: FakeChatApi): List<String> =
        api.requests[1].messages.filter { it.role == "tool" }.map { it.content!! }

    @Test
    fun `all eight tools are registered`() {
        val names = AiAgent(FakeChatApi(mutableListOf()), ToolProject()).tools().map { it.function.name }
        assertEquals(
            listOf(
                "read_file", "write_file", "list_files", "search_files",
                "delete_file", "set_app_config", "get_build_status", "git_log",
            ),
            names,
        )
    }

    @Test
    fun `search files returns path line matches`() {
        val api = runWithCall(ToolProject(), toolCall("c1", "search_files", """{"pattern":"hello"}"""))

        val result = toolResults(api).single()
        assertTrue(result.contains("README.md:1: # hello world"))
        assertTrue(result.contains("app/src/main/java/com/t/A.kt:2: val greeting = \"hello\""))
    }

    @Test
    fun `search files reports invalid regex and no matches`() {
        val api = runWithCall(ToolProject(), toolCall("c1", "search_files", """{"pattern":"(["}"""))
        assertTrue(toolResults(api).single().contains("error"))

        val api2 = runWithCall(ToolProject(), toolCall("c1", "search_files", """{"pattern":"zzzz"}"""))
        assertTrue(toolResults(api2).single().contains("no matches"))
    }

    @Test
    fun `delete file marks deletion and reports missing files`() {
        val project = ToolProject()
        val api = runWithCall(project, toolCall("c1", "delete_file", """{"path":"README.md"}"""))

        assertTrue(toolResults(api).single().contains("ok"))
        assertEquals(listOf("README.md"), project.deletes)

        val api2 = runWithCall(ToolProject(), toolCall("c2", "delete_file", """{"path":"nope.kt"}"""))
        assertTrue(toolResults(api2).single().contains("error"))
    }

    @Test
    fun `set app config forwards name and id, errors propagate`() {
        val project = ToolProject()
        val api = runWithCall(
            project,
            toolCall("c1", "set_app_config", """{"app_name":"নতুন নাম","application_id":"com.t.new"}"""),
        )

        assertTrue(toolResults(api).single().contains("ok"))
        assertEquals("নতুন নাম" to "com.t.new", project.appliedConfig)

        val failing = ToolProject().apply { configError = "bad id" }
        val api2 = runWithCall(failing, toolCall("c1", "set_app_config", """{"application_id":"com.t.new"}"""))
        assertTrue(toolResults(api2).single().contains("bad id"))
    }

    @Test
    fun `build status and git log surface to the model`() {
        val api = runWithCall(
            ToolProject(),
            toolCall("c1", "get_build_status", "{}"),
            toolCall("c2", "git_log", """{"limit":5}"""),
        )

        val results = toolResults(api)
        assertTrue(results[0].contains("completed: success"))
        assertTrue(results[1].contains("abc1234 initial commit"))
        assertTrue(results[1].contains("def5678"))
    }

    @Test
    fun `list files returns the paths`() {
        val api = runWithCall(ToolProject(), toolCall("c1", "list_files", "{}"))

        val result = toolResults(api).single()
        assertTrue(result.contains("app/src/main/java/com/t/A.kt"))
        assertTrue(result.contains("README.md"))
    }
}
