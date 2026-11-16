package io.github.differentialmanifold.jagentharness.core.identity;

import java.util.Collections;
import java.util.List;

public interface UserDirectory {
    List<String> userIds();

    UserDirectory LOCAL = () -> Collections.singletonList(UserContext.LOCAL);

    default void forEach(Runnable action) {
        for (String user : userIds())
            try (UserContext ignored = UserContext.enter(user)) {
                action.run();
            }
    }
    /** A transient registry failure must not permanently cancel a scheduled scanner. */
    default void scan(Runnable action) {
        try {
            for (String user : userIds())
                try (UserContext ignored = UserContext.enter(user)) {
                    action.run();
                } catch (RuntimeException error) {
                    java.util.logging.Logger.getLogger(UserDirectory.class.getName())
                            .log(
                                    java.util.logging.Level.WARNING,
                                    "User background check failed; retrying on next scan",
                                    error);
                }
        } catch (RuntimeException error) {
            java.util.logging.Logger.getLogger(UserDirectory.class.getName())
                    .log(
                            java.util.logging.Level.WARNING,
                            "User directory unavailable; retrying on next scan",
                            error);
        }
    }
}
