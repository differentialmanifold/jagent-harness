package io.github.differentialmanifold.jagentharness.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class McpServerConfig {

    public static final String STREAMABLE_HTTP = "streamable-http";

    private String name;
    private String transport = STREAMABLE_HTTP;
    private String url;
    private boolean enabled = true;
    private Map<String, String> headers = new LinkedHashMap<String, String>();
    private int connectTimeoutSeconds = 10;
    private int requestTimeoutSeconds = 60;

    public McpServerConfig() {
    }

    public McpServerConfig copy() {
        McpServerConfig copy = new McpServerConfig();
        copy.name = name;
        copy.transport = transport;
        copy.url = url;
        copy.enabled = enabled;
        copy.headers = new LinkedHashMap<String, String>(headers);
        copy.connectTimeoutSeconds = connectTimeoutSeconds;
        copy.requestTimeoutSeconds = requestTimeoutSeconds;
        return copy;
    }

    @JsonIgnore
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getTransport() {
        return transport;
    }

    public void setTransport(String transport) {
        this.transport = transport;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public void setHeaders(Map<String, String> headers) {
        this.headers = headers == null
                ? new LinkedHashMap<String, String>()
                : new LinkedHashMap<String, String>(headers);
    }

    public int getConnectTimeoutSeconds() {
        return connectTimeoutSeconds;
    }

    public void setConnectTimeoutSeconds(int connectTimeoutSeconds) {
        this.connectTimeoutSeconds = connectTimeoutSeconds;
    }

    public int getRequestTimeoutSeconds() {
        return requestTimeoutSeconds;
    }

    public void setRequestTimeoutSeconds(int requestTimeoutSeconds) {
        this.requestTimeoutSeconds = requestTimeoutSeconds;
    }
}
