package com.hasan.v1.webui.models

/** Un serveur MCP configuré côté hermes-webui (~/.hermes/config.yaml, mcp_servers) — GET /api/mcp/servers. */
data class McpServer(
    val name: String,
    val enabled: Boolean,
    val toggleSupported: Boolean
)
