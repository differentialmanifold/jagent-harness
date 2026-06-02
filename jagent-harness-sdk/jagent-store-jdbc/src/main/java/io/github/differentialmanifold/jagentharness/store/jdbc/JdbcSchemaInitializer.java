package io.github.differentialmanifold.jagentharness.store.jdbc;

import javax.sql.DataSource;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;

public class JdbcSchemaInitializer {

    public static final String DEFAULT_SCHEMA_LOCATION = "db/jagent-harness/schema.sql";

    private final DataSource dataSource;
    private final Resource schemaResource;

    public JdbcSchemaInitializer(DataSource dataSource) {
        this(dataSource, new ClassPathResource(DEFAULT_SCHEMA_LOCATION));
    }

    public JdbcSchemaInitializer(DataSource dataSource, Resource schemaResource) {
        this.dataSource = dataSource;
        this.schemaResource = schemaResource;
    }

    public void initialize() {
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(schemaResource);
        populator.setSqlScriptEncoding("UTF-8");
        DatabasePopulatorUtils.execute(populator, dataSource);
    }
}
