package com.enajid.apkbuilder.data.ai

import com.enajid.apkbuilder.data.AppContainer
import com.enajid.apkbuilder.data.TemplateRenderer
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Hosts an MCP server on the phone so Claude / ChatGPT can connect to the
 * user's APK Builder projects as a custom connector — the same idea as the
 * Canva / Google Drive connectors.
 *
 * Shape: NanoHTTPD on 127.0.0.1 (random port) + a pinggy SSH tunnel
 * (port 443) exposing it as a public HTTPS URL. The URL path carries a
 * random 32-hex token, so the unguessable URL itself is the auth — anyone
 * without the exact link gets a 404.
 *
 * Tools are GitHub-backed and work even when the app is in the background
 * (as long as Android keeps the process alive): list_projects, list_files,
 * read_file, write_file, trigger_build, get_build_status.
 *
 * Free pinggy tunnels close after ~60 minutes; the watcher surfaces that
 * and the user just toggles the server on again.
 */
class McpHost(private val container: AppContainer) {

    sealed interface State {
        data object Idle : State
        data object Starting : State
        data class Running(val url: String) : State
        data class Failed(val reason: String) : State
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var server: PhoneMcpServer? = null

    @Volatile
    private var sshSession: Session? = null

    @Volatile
    private var watcher: Job? = null

    /** Unguessable path token — regenerated on every start. */
    @Volatile
    private var token: String = ""

    fun start() {
        if (_state.value is State.Running || _state.value is State.Starting) return
        _state.value = State.Starting
        scope.launch {
            try {
                token = newToken()
                val srv = PhoneMcpServer(token, dispatcher())
                srv.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                server = srv
                val url = openTunnel(srv.listeningPort)
                _state.value = State.Running(url)
                AiDebugLog.ok("mcp-host", "MCP server চালু: $url")
                startWatcher()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AiDebugLog.error("mcp-host", "MCP server চালু করা যায়নি", e)
                stopEverything()
                _state.value = State.Failed(e.message ?: "অজানা সমস্যা")
            }
        }
    }

    fun stop() {
        stopEverything()
        _state.value = State.Idle
    }

    private fun stopEverything() {
        watcher?.cancel()
        watcher = null
        runCatching { sshSession?.disconnect() }
        sshSession = null
        runCatching { server?.stop() }
        server = null
    }

    /** Marks the host failed once the SSH session drops (pinggy 60-min limit). */
    private fun startWatcher() {
        watcher = scope.launch {
            while (isActive) {
                delay(30_000)
                val session = sshSession
                if (session != null && !session.isConnected) {
                    stopEverything()
                    _state.value = State.Failed(
                        "tunnel বন্ধ হয়ে গেছে (pinggy free ~60 মিনিট পর বন্ধ হয়) — আবার চালু করো"
                    )
                    return@launch
                }
            }
        }
    }

    // ------------------------------------------------------------- tunnel --

    /**
     * Opens a pinggy tunnel: SSH remote-forward of a dynamic remote port to
     * the local server. The public URL is announced in the SSH banner, which
     * JSch hands to [UserInfo.showMessage].
     */
    private fun openTunnel(localPort: Int): String {
        val jsch = JSch()
        val session = jsch.getSession("apkbuilder", "a.pinggy.io", 443)
        session.setConfig("StrictHostKeyChecking", "no")
        session.setServerAliveInterval(25)
        session.setServerAliveCountMax(4)
        val bannerCaptor = TunnelBannerCaptor()
        session.userInfo = bannerCaptor
        session.connect(20_000)
        try {
            // Dynamic remote port (0) — pinggy assigns and announces the URL.
            session.setPortForwardingR("", 0, "127.0.0.1", localPort)
            val deadline = System.currentTimeMillis() + 15_000
            while (System.currentTimeMillis() < deadline) {
                bannerCaptor.url()?.let { url ->
                    sshSession = session
                    return url
                }
                Thread.sleep(500)
            }
            error("tunnel URL পাওয়া যায়নি — pinggy উত্তর দেয়নি (আবার চেষ্টা করো)")
        } catch (e: Exception) {
            runCatching { session.disconnect() }
            throw e
        }
    }

    /** Captures the SSH banner (pinggy announces the tunnel URL there). */
    private class TunnelBannerCaptor : UserInfo {
        private val messages = mutableListOf<String>()

        fun url(): String? = synchronized(messages) {
            messages
                .flatMap { URL_REGEX.findAll(it).map { m -> m.value } }
                .firstOrNull { it.startsWith("https://") }
        }

        override fun showMessage(message: String) {
            synchronized(messages) { messages += message }
        }

        override fun promptYesNo(message: String) = false
        override fun promptPassword(message: String) = false
        override fun promptPassphrase(message: String) = false
        override fun getPassword() = null
        override fun getPassphrase() = null
    }

    // -------------------------------------------------------------- tools --

    private fun dispatcher(): PhoneMcpDispatcher {
        val tools = listOf(
            tool(
                "list_projects",
                "List the user's APK Builder projects on GitHub (their app projects).",
            ),
            tool(
                "list_files",
                "List every file path in one project.",
                "repo" to "Repository name, e.g. my-app",
            ),
            tool(
                "read_file",
                "Read one text file from a project.",
                "repo" to "Repository name",
                "path" to "Project-relative file path",
            ),
            tool(
                "write_file",
                "Create or overwrite a file in a project and commit it to GitHub. " +
                    "Write complete, compilable file contents.",
                "repo" to "Repository name",
                "path" to "Project-relative file path",
                "content" to "The full new file content",
            ),
            tool(
                "trigger_build",
                "Trigger a GitHub Actions build for a project (creates a commit so " +
                    "the push-triggered workflow runs).",
                "repo" to "Repository name",
            ),
            tool(
                "get_build_status",
                "Get the latest GitHub Actions build for a project: status, " +
                    "conclusion and failing steps.",
                "repo" to "Repository name",
            ),
        )
        return PhoneMcpDispatcher(tools = tools) { name, args ->
            try {
                callTool(name, args)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "error: ${e.message}" to true
            }
        }
    }

    private fun tool(name: String, description: String, vararg props: Pair<String, String>) =
        PhoneMcpTool(
            name = name,
            description = description,
            inputSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        props.forEach { (prop, desc) ->
                            put(
                                prop,
                                buildJsonObject {
                                    put("type", "string")
                                    put("description", desc)
                                },
                            )
                        }
                    },
                )
            },
        )

    private suspend fun callTool(name: String, argsJson: String): Pair<String, Boolean> {
        val owner = container.tokenStore.loginFlow.first()
            ?: return "error: the phone is signed out of GitHub — open APK Builder and sign in" to true
        val args = runCatching { json.parseToJsonElement(argsJson).jsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
        fun arg(key: String): String? =
            (args[key] as? JsonPrimitive)?.contentOrNull?.trim()

        when (name) {
            "list_projects" -> {
                val repos = container.projectsRepository.listProjects()
                if (repos.isEmpty()) {
                    "no APK Builder projects yet — create one in the app first" to false
                } else {
                    repos.joinToString("\n") { repo ->
                        "- ${repo.name} (branch ${repo.default_branch.ifBlank { "main" }})"
                    } to false
                }
            }
            "list_files" -> {
                val repo = arg("repo") ?: return "error: missing 'repo'" to true
                val branch = branchOf(owner, repo)
                val paths = container.gitRepository.listFiles(owner, repo, branch).map { it.path }
                if (paths.isEmpty()) "(empty repository)" to false else paths.joinToString("\n") to false
            }
            "read_file" -> {
                val repo = arg("repo") ?: return "error: missing 'repo'" to true
                val path = arg("path") ?: return "error: missing 'path'" to true
                val branch = branchOf(owner, repo)
                val bytes = container.gitRepository.readFile(owner, repo, branch, path)
                if (bytes.contains(0.toByte())) "(binary file)" to false
                else String(bytes, Charsets.UTF_8).take(50_000) to false
            }
            "write_file" -> {
                val repo = arg("repo") ?: return "error: missing 'repo'" to true
                val path = arg("path") ?: return "error: missing 'path'" to true
                val content = args["content"]?.let { (it as? JsonPrimitive)?.contentOrNull }
                    ?: return "error: missing 'content'" to true
                if (path.isBlank() || path.startsWith("/") || path.split('/').contains("..")) {
                    return "error: invalid path" to true
                }
                val branch = branchOf(owner, repo)
                val sha = container.gitRepository.pushFiles(
                    owner = owner,
                    repo = repo,
                    branch = branch,
                    files = listOf(
                        TemplateRenderer.RenderedFile(path, content.toByteArray(Charsets.UTF_8))
                    ),
                    message = "Write $path (via MCP connector)",
                )
                "ok: committed $path to $repo@${branch} (${sha.take(7)})" to false
            }
            "trigger_build" -> {
                val repo = arg("repo") ?: return "error: missing 'repo'" to true
                val branch = branchOf(owner, repo)
                val sha = container.gitRepository.createEmptyCommit(
                    owner, repo, branch, "Trigger build (via MCP connector)",
                )
                "ok: build triggered for $repo — commit ${sha.take(7)}. " +
                    "Check get_build_status in a minute." to false
            }
            "get_build_status" -> {
                val repo = arg("repo") ?: return "error: missing 'repo'" to true
                val summary = container.gitRepository.latestBuildSummary(owner, repo)
                    ?: return "no workflow runs yet for $repo" to false
                summary to false
            }
            else -> "error: unknown tool '$name'" to true
        }
    }

    private suspend fun branchOf(owner: String, repo: String): String =
        runCatching {
            container.projectsRepository.getRepo(owner, repo).default_branch.ifBlank { "main" }
        }.getOrDefault("main")

    private fun newToken(): String {
        val chars = "0123456789abcdef"
        return buildString { repeat(32) { append(chars.random()) } }
    }

    companion object {
        private val URL_REGEX = Regex("""https://[A-Za-z0-9.-]+[A-Za-z0-9]/?[^\s"]*""")
    }
}

/**
 * The NanoHTTPD layer: only POST /<token>/mcp is served; everything else is
 * a 404 so random scanners learn nothing about the endpoint.
 */
internal class PhoneMcpServer(
    private val token: String,
    private val dispatcher: PhoneMcpDispatcher,
) : NanoHTTPD("127.0.0.1", 0) {

    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/$token/mcp") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
        }
        if (session.method != Method.POST) {
            return newFixedLengthResponse(
                Response.Status.METHOD_NOT_ALLOWED,
                "text/plain",
                "POST only",
            )
        }
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val body = files["postData"] ?: ""
            val result = runBlocking { dispatcher.handle(body) }
            val response = newFixedLengthResponse(
                if (result.body == null) Response.Status.ACCEPTED else Response.Status.OK,
                "application/json",
                result.body ?: "",
            )
            result.sessionId?.let { response.addHeader("Mcp-Session-Id", it) }
            response
        } catch (e: Exception) {
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"jsonrpc":"2.0","id":null,"error":{"code":-32603,"message":"internal: ${e.message?.replace("\"", "'")?.take(200)}"}}""",
            )
        }
    }
}
