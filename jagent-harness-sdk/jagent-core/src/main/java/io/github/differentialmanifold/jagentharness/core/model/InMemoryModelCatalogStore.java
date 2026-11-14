package io.github.differentialmanifold.jagentharness.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Fallback for applications that supply no persistent store. */
public final class InMemoryModelCatalogStore implements ModelCatalogStore {
    private final ObjectMapper json = new ObjectMapper();
    private Configuration configuration;
    private final Map<List<String>, Selection> selections = new HashMap<>();

    public synchronized Configuration configuration() {
        return copy(configuration, Configuration.class);
    }

    public synchronized boolean save(Configuration c) {
        if (c.revision != (configuration == null ? 0 : configuration.revision)) return false;
        configuration = copy(c, Configuration.class);
        configuration.revision++;
        return true;
    }

    public synchronized Selection selection(String session, String run) {
        return copy(selections.get(Arrays.asList(session, run)), Selection.class);
    }

    public synchronized void select(String session, String run, Selection selected) {
        selections.put(Arrays.asList(session, run), copy(selected, Selection.class));
    }

    private <T> T copy(T value, Class<T> type) {
        return value == null ? null : json.convertValue(value, type);
    }
}
