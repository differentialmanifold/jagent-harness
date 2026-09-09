package io.github.differentialmanifold.jagentharness.store.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "harness.approval.jdbc")
public class JdbcToolApprovalProperties {

    private long pollIntervalMillis = 1000L;

    public long getPollIntervalMillis() {
        return pollIntervalMillis;
    }

    public void setPollIntervalMillis(long pollIntervalMillis) {
        this.pollIntervalMillis = pollIntervalMillis;
    }
}
