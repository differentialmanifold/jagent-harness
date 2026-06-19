package io.github.differentialmanifold.jagentharness.store.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "harness.store.jdbc")
public class JdbcStoreProperties {

    private String applicationId = "default";

    public String getApplicationId() {
        return applicationId;
    }

    public void setApplicationId(String applicationId) {
        this.applicationId = applicationId;
    }

    public String requireApplicationId() {
        String value = applicationId == null ? "" : applicationId.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("harness.store.jdbc.application-id is required");
        }
        if (value.length() > 128) {
            throw new IllegalArgumentException("harness.store.jdbc.application-id must contain 1-128 characters");
        }
        return value;
    }
}
