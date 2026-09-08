package io.github.differentialmanifold.jagentharness.store.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "agent.store.jdbc")
public class JdbcStoreProperties {

    private boolean initializeSchema = true;
    private String platform = "auto";
    private java.util.List<String> schemaLocations = new java.util.ArrayList<>();

    public boolean isInitializeSchema() {
        return initializeSchema;
    }

    public void setInitializeSchema(boolean value) {
        initializeSchema = value;
    }

    public String getPlatform() {
        return platform;
    }

    public void setPlatform(String value) {
        platform = value;
    }

    public java.util.List<String> getSchemaLocations() {
        return schemaLocations;
    }

    public void setSchemaLocations(java.util.List<String> value) {
        schemaLocations = value;
    }

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
            throw new IllegalArgumentException("agent.store.jdbc.application-id is required");
        }
        if (value.length() > 128) {
            throw new IllegalArgumentException(
                    "agent.store.jdbc.application-id must contain 1-128 characters");
        }
        return value;
    }
}
