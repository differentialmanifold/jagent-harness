package io.github.differentialmanifold.jagentharness.mcp;

final class SseEvent {

    private final String id;
    private final String data;

    SseEvent(String id, String data) {
        this.id = id;
        this.data = data;
    }

    String getId() {
        return id;
    }

    String getData() {
        return data;
    }
}
