package io.github.differentialmanifold.jagentharness.mcp;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

final class SseEventReader {

    List<SseEvent> read(InputStream input) throws IOException {
        List<SseEvent> events = new ArrayList<SseEvent>();
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
        String id = null;
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                append(events, id, data);
                id = null;
                data.setLength(0);
                continue;
            }
            if (line.startsWith(":")) {
                continue;
            }
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) {
                value = value.substring(1);
            }
            if ("id".equals(field)) {
                id = value;
            } else if ("data".equals(field)) {
                if (data.length() > 0) {
                    data.append('\n');
                }
                data.append(value);
            }
        }
        append(events, id, data);
        return events;
    }

    private void append(List<SseEvent> events, String id, StringBuilder data) {
        if (id != null || data.length() > 0) {
            events.add(new SseEvent(id, data.toString()));
        }
    }
}
