package io.github.differentialmanifold.jagentharness.mcp;

final class McpSessionExpiredException extends McpProtocolException {

    McpSessionExpiredException() {
        super("MCP session expired");
    }
}
