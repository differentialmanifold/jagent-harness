package io.github.differentialmanifold.jagentharness.mcp;

import java.io.IOException;

public class McpProtocolException extends IOException {

    public McpProtocolException(String message) {
        super(message);
    }

    public McpProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}
