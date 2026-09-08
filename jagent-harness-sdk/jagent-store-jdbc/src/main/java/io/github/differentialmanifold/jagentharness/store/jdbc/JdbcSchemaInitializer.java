package io.github.differentialmanifold.jagentharness.store.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;
import javax.sql.DataSource;
import org.springframework.core.io.*;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

/** Initializes a fresh schema; existing installations should use host-managed migrations. */
public class JdbcSchemaInitializer {
    private final DataSource dataSource;
    private final JdbcStoreProperties properties;
    private final ResourceLoader resources;
    private final Resource explicitSchema;

    public JdbcSchemaInitializer(DataSource dataSource) {
        this(dataSource, new JdbcStoreProperties(), new DefaultResourceLoader());
    }

    public JdbcSchemaInitializer(DataSource dataSource, Resource schemaResource) {
        this.dataSource = dataSource;
        this.properties = new JdbcStoreProperties();
        this.resources = new DefaultResourceLoader();
        this.explicitSchema = Objects.requireNonNull(schemaResource);
    }

    public JdbcSchemaInitializer(
            DataSource dataSource, JdbcStoreProperties properties, ResourceLoader resources) {
        this.dataSource = dataSource;
        this.properties = properties;
        this.resources = resources;
        this.explicitSchema = null;
    }

    public void initialize() {
        if (!properties.isInitializeSchema()) return;
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.setSqlScriptEncoding("UTF-8");
        if (explicitSchema != null) {
            populator.addScript(explicitSchema);
        } else if (!properties.getSchemaLocations().isEmpty()) {
            for (String location : properties.getSchemaLocations())
                populator.addScript(resources.getResource(location));
        } else {
            populator.addScript(
                    resources.getResource(
                            "classpath:db/jagent-harness/schema-" + platform() + ".sql"));
        }
        DatabasePopulatorUtils.execute(populator, dataSource);
    }

    private String platform() {
        String name =
                properties.getPlatform() == null
                        ? "auto"
                        : properties.getPlatform().trim().toLowerCase(Locale.ROOT);
        if ("auto".equals(name)) {
            try (Connection connection = dataSource.getConnection()) {
                name = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT);
            } catch (SQLException e) {
                throw new IllegalStateException("Cannot detect the database platform", e);
            }
        }
        if (!Arrays.asList("sqlite", "h2", "postgresql").contains(name)) {
            throw new IllegalStateException(
                    "No bundled schema for database '"
                            + name
                            + "'. Set agent.store.jdbc.schema-locations to compatible SQL scripts, or set "
                            + "agent.store.jdbc.initialize-schema=false and manage the schema in your application.");
        }
        return name;
    }
}
