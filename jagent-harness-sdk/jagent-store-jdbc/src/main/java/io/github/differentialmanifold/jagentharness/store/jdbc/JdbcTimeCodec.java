package io.github.differentialmanifold.jagentharness.store.jdbc;

import java.time.Instant;

final class JdbcTimeCodec {

    private JdbcTimeCodec() {
    }

    static String encode(Instant value) {
        return value == null ? null : value.toString();
    }

    static Instant decode(String value) {
        return value == null ? null : Instant.parse(value);
    }
}
