package io.github.differentialmanifold.jagentharness.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.identity.UserState;
import java.util.*;

/** Fallback for applications that supply no persistent store. */
public final class InMemoryModelCatalogStore implements ModelCatalogStore {
    private final ObjectMapper json = new ObjectMapper();
    private final UserState<State> users = new UserState<>(State::new);

    private static final class State {
        Configuration configuration;
        final Map<List<String>, Selection> selections = new HashMap<>();
    }

    public synchronized Configuration configuration() {
        return copy(users.get().configuration, Configuration.class);
    }

    public synchronized boolean save(Configuration c) {
        State state = users.get();
        if (c.revision != (state.configuration == null ? 0 : state.configuration.revision))
            return false;
        state.configuration = copy(c, Configuration.class);
        state.configuration.revision++;
        return true;
    }

    public synchronized Selection selection(String session, String run) {
        return copy(users.get().selections.get(Arrays.asList(session, run)), Selection.class);
    }

    public synchronized void select(String session, String run, Selection selected) {
        users.get().selections.put(Arrays.asList(session, run), copy(selected, Selection.class));
    }

    private <T> T copy(T value, Class<T> type) {
        return value == null ? null : json.convertValue(value, type);
    }
}
