package io.github.differentialmanifold.jagentharness.store.jdbc;

/** Validate bounded text before writes, including on SQLite which ignores VARCHAR lengths. */
final class JdbcFields {
    private JdbcFields() {}

    static void maxLength(String value, String field, int maximum) {
        if (value != null && value.length() > maximum)
            throw new IllegalArgumentException(
                    field + " must contain at most " + maximum + " characters");
    }
}
