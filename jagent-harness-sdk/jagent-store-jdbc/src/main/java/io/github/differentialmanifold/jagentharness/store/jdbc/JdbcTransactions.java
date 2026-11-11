package io.github.differentialmanifold.jagentharness.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Short storage-only write transactions; never wrap model calls or external side effects. */
final class JdbcTransactions {
    private JdbcTransactions() {}

    static TransactionTemplate write(JdbcTemplate jdbc) {
        return new TransactionTemplate(
                new DataSourceTransactionManager(jdbc.getDataSource()) {
                    @Override
                    protected void prepareTransactionalConnection(
                            Connection connection, TransactionDefinition definition)
                            throws SQLException {
                        super.prepareTransactionalConnection(connection, definition);
                        if (!definition.isReadOnly()
                                && "SQLite"
                                        .equalsIgnoreCase(
                                                connection
                                                        .getMetaData()
                                                        .getDatabaseProductName())) {
                            // SQLite starts a write transaction even if UPDATE matches no rows.
                            // Acquire
                            // its database write lock before any reads, avoiding a deferred lock
                            // upgrade.
                            // Keep normal JDBC commit/rollback and pool cleanup; no driver-specific
                            // API,
                            // row changes, Java mutex, or PostgreSQL locking changes are needed.
                            try (Statement statement = connection.createStatement()) {
                                statement.executeUpdate(
                                        "update projects set project_id = project_id where 1 = 0");
                            }
                        }
                    }
                });
    }
}
