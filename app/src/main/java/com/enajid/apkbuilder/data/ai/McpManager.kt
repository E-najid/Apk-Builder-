package com.enajid.apkbuilder.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonElement

/** What [AiAgent] needs from the connected MCP servers. */
interface McpToolProvider {
    /** Prefixed tool specs ("mcp__slug__name") ready for the model. */
    suspend fun tools(): List<ToolSpec>

    /** Executes one prefixed tool call, returns the result text for the model. */
    suspend fun call(name: String, argumentsJson: String): String
}

/**
 * Owns the configured MCP servers: caches each server's tool list,
 * prefixes tool names so they can't collide with the built-in tools, and
 * runs calls through the user-confirmation hook the editor installs.
 */
class McpManager : McpToolProvider {

    data class ServerTools(val slug: String, val tools: List<ToolSpec>, val client: McpClient)

    @Volatile
    private var servers: List<McpServerConfig> = emptyList()

    private val toolCache = mutableMapOf<Long, ServerTools>()

    /** Set by the UI host: asks the user whether a NEW tool may run. */
    @Volatile
    var confirmHook: (suspend (serverSlug: String, tool: String, args: String) -> Boolean)? = null

    /** (serverSlug, tool) pairs already approved in the current agent run. */
    private val approvedThisRun = mutableSetOf<String>()

    fun updateConfigs(list: List<McpServerConfig>) {
        servers = list
        synchronized(toolCache) { toolCache.clear() }
    }

    /** Clears per-run approvals; call when a new agent task starts. */
    fun beginRun() {
        synchronized(approvedThisRun) { approvedThisRun.clear() }
    }

    /**
     * Connects to every enabled server (cached) and returns the total tool
     * count plus a notice line per server that failed — the chat shows
     * those so a broken server is never silent.
     */
    suspend fun prepare(): Pair<Int, List<String>> {
        val notices = mutableListOf<String>()
        var count = 0
        for (server in servers.filter { it.enabled }) {
            val cached = synchronized(toolCache) { toolCache[server.id] }
            if (cached != null) {
                count += cached.tools.size
                continue
            }
            try {
                val client = McpClient(server.url, server.token)
                client.initialize()
                val slug = McpProtocol.slugFromName(server.name, server.id)
                val tools = client.listTools().map { t ->
                    ToolSpec(
                        function = FunctionSpec(
                            name = McpProtocol.prefixedToolName(slug, t.name),
                            description = "[mcp: ${server.name}] ${t.description}".trim(),
                            parameters = t.inputSchema,
                        ),
                    )
                }
                synchronized(toolCache) { toolCache[server.id] = ServerTools(slug, tools, client) }
                count += tools.size
                AiDebugLog.ok("mcp", "\"${server.name}\" → ${tools.size} tool(s)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                AiDebugLog.warn("mcp", "MCP server \"${server.name}\" connect ব্যর্থ: ${e.message}")
                notices += "⚠ MCP server \"${server.name}\" connect করা যায়নি: ${e.message}"
            }
        }
        return count to notices
    }

    override suspend fun tools(): List<ToolSpec> =
        synchronized(toolCache) { toolCache.values.flatMap { it.tools } }

    override suspend fun call(name: String, argumentsJson: String): String {
        val (slug, tool) = McpProtocol.splitPrefixed(name)
            ?: return "error: not an mcp tool: $name"
        val entry = synchronized(toolCache) { toolCache.values.firstOrNull { it.slug == slug } }
            ?: return "error: unknown MCP server for $name (server disabled or offline)"
        val key = "$slug/$tool"
        val approved = synchronized(approvedThisRun) { key in approvedThisRun }
        if (!approved) {
            val allowed = confirmHook?.invoke(slug, tool, argumentsJson) ?: true
            if (!allowed) return "error: user declined this MCP tool call"
            synchronized(approvedThisRun) { approvedThisRun += key }
        }
        return try {
            val (text, isError) = entry.client.callTool(tool, argumentsJson)
            when {
                isError -> "error: $text"
                text.isBlank() -> "(empty response from MCP tool)"
                else -> text
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AiException) {
            "error: ${e.message}"
        } catch (e: Exception) {
            "error: MCP call failed: ${e.message}"
        }
    }
}
