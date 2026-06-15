package io.github.differentialmanifold.jagentharness.store.jdbc;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "harness.stop.jdbc")
public class JdbcRunStopProperties {

    private long pollIntervalMillis = 100L;
    private long leaseDurationMillis = 10000L;
    private int listenerThreads = 2;

    public long getPollIntervalMillis() {
        return pollIntervalMillis;
    }

    public void setPollIntervalMillis(long pollIntervalMillis) {
        this.pollIntervalMillis = pollIntervalMillis;
    }

    public long getLeaseDurationMillis() {
        return leaseDurationMillis;
    }

    public void setLeaseDurationMillis(long leaseDurationMillis) {
        this.leaseDurationMillis = leaseDurationMillis;
    }

    public int getListenerThreads() {
        return listenerThreads;
    }

    public void setListenerThreads(int listenerThreads) {
        this.listenerThreads = listenerThreads;
    }
}
