package io.github.differentialmanifold.jagentharness.spring.web;

import java.util.ArrayList;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

@ConfigurationProperties(prefix = "agent.http")
public class ConsoleProperties {

    private List<String> allowedOrigins = new ArrayList<String>();
    private DataSize maxChatRequestBodySize = DataSize.ofMegabytes(32);

    public ConsoleProperties() {
        allowedOrigins.add("http://localhost:5175");
        allowedOrigins.add("http://127.0.0.1:5175");
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public DataSize getMaxChatRequestBodySize() {
        return maxChatRequestBodySize;
    }

    public void setMaxChatRequestBodySize(DataSize maxChatRequestBodySize) {
        if (maxChatRequestBodySize == null || maxChatRequestBodySize.toBytes() <= 0L) {
            throw new IllegalArgumentException("maxChatRequestBodySize must be greater than zero");
        }
        this.maxChatRequestBodySize = maxChatRequestBodySize;
    }
}
