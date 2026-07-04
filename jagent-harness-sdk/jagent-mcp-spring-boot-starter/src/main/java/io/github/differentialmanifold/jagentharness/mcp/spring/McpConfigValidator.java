package io.github.differentialmanifold.jagentharness.mcp.spring;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.differentialmanifold.jagentharness.mcp.McpServerConfig;
import okhttp3.HttpUrl;

public class McpConfigValidator {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final Pattern ENVIRONMENT = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)}");
    private static final Set<String> RESERVED_HEADERS = new HashSet<String>(Arrays.asList(
            "accept", "content-type", "host", "mcp-session-id", "mcp-protocol-version"));

    public McpServerConfig validate(String name, McpServerConfig input) {
        return validate(name, input, false);
    }

    public McpServerConfig validateAndResolve(String name, McpServerConfig input) {
        return validate(name, input, true);
    }

    private McpServerConfig validate(String name, McpServerConfig input, boolean resolveEnvironment) {
        if (!NAME.matcher(name == null ? "" : name).matches()) {
            throw new IllegalArgumentException("MCP server name must match [A-Za-z0-9_-]{1,64}: " + name);
        }
        if (input == null) {
            throw new IllegalArgumentException("MCP server configuration is required: " + name);
        }
        McpServerConfig config = input.copy();
        config.setName(name);
        if (!McpServerConfig.STREAMABLE_HTTP.equals(config.getTransport())) {
            throw new IllegalArgumentException("Unsupported MCP transport for " + name + ": " + config.getTransport());
        }
        HttpUrl url = HttpUrl.parse(config.getUrl());
        if (url == null || !("http".equals(url.scheme()) || "https".equals(url.scheme()))) {
            throw new IllegalArgumentException("MCP server URL must use http or https: " + name);
        }
        if (config.getConnectTimeoutSeconds() < 1 || config.getConnectTimeoutSeconds() > 3600
                || config.getRequestTimeoutSeconds() < 1 || config.getRequestTimeoutSeconds() > 3600) {
            throw new IllegalArgumentException("MCP timeouts must be between 1 and 3600 seconds: " + name);
        }
        if (config.getEnabledTools() != null) {
            Set<String> names = new LinkedHashSet<String>();
            for (String tool : config.getEnabledTools()) {
                String toolName = tool == null ? "" : tool.trim();
                if (toolName.isEmpty()) {
                    throw new IllegalArgumentException("MCP enabled tool name must not be empty: " + config.getName());
                }
                names.add(toolName);
            }
            config.setEnabledTools(new ArrayList<String>(names));
        }
        for (Map.Entry<String, String> header : config.getHeaders().entrySet()) {
            String headerName = header.getKey() == null ? "" : header.getKey().trim();
            String lowerName = headerName.toLowerCase(Locale.ROOT);
            if (headerName.isEmpty() || RESERVED_HEADERS.contains(lowerName)) {
                throw new IllegalArgumentException("Reserved or empty MCP header: " + headerName);
            }
            String rawValue = header.getValue() == null ? "" : header.getValue();
            if (isSensitive(lowerName) && !ENVIRONMENT.matcher(rawValue).find()) {
                throw new IllegalArgumentException("Sensitive MCP header must reference an environment variable: " + headerName);
            }
            if (resolveEnvironment) {
                header.setValue(resolveEnvironment(rawValue));
            }
        }
        return config;
    }

    private boolean isSensitive(String name) {
        return "authorization".equals(name)
                || "proxy-authorization".equals(name)
                || "cookie".equals(name)
                || name.contains("api-key")
                || name.contains("token");
    }

    private String resolveEnvironment(String value) {
        Matcher matcher = ENVIRONMENT.matcher(value);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String resolved = System.getenv(matcher.group(1));
            if (resolved == null) {
                throw new IllegalArgumentException("Required MCP environment variable is not set: " + matcher.group(1));
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(resolved));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
