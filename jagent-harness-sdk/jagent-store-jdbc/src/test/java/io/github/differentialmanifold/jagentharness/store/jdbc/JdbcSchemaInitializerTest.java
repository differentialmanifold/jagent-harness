package io.github.differentialmanifold.jagentharness.store.jdbc;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.*;
import org.springframework.jdbc.core.JdbcTemplate;

class JdbcSchemaInitializerTest {
    @Test
    void disabledInitializationDoesNotTouchDatabase() {
        DataSource database = mock(DataSource.class);
        JdbcStoreProperties settings = new JdbcStoreProperties();
        settings.setInitializeSchema(false);
        new JdbcSchemaInitializer(database, settings, new DefaultResourceLoader()).initialize();
        verifyNoInteractions(database);
    }

    @Test
    void hostScriptReplacesBundledSchemaAndCanRunRepeatedly() {
        JdbcDataSource database = new JdbcDataSource();
        database.setURL("jdbc:h2:mem:custom-schema;DB_CLOSE_DELAY=-1");
        JdbcStoreProperties settings = new JdbcStoreProperties();
        settings.setPlatform("custom-database");
        settings.setSchemaLocations(Collections.singletonList("classpath:host.sql"));
        ResourceLoader loader = mock(ResourceLoader.class);
        when(loader.getResource("classpath:host.sql"))
                .thenReturn(
                        new ByteArrayResource(
                                "create table if not exists host_owned (id integer primary key);"
                                        .getBytes(StandardCharsets.UTF_8)));
        JdbcSchemaInitializer initializer = new JdbcSchemaInitializer(database, settings, loader);
        initializer.initialize();
        initializer.initialize();
        JdbcTemplate db = new JdbcTemplate(database);
        assertEquals(0, db.queryForObject("select count(*) from host_owned", Integer.class));
        assertEquals(
                0,
                db.queryForObject(
                        "select count(*) from information_schema.tables where table_name='AGENT_CONVERSATIONS'",
                        Integer.class));
    }

    @Test
    void unknownPlatformFailsWithAnActionableMessage() {
        JdbcStoreProperties settings = new JdbcStoreProperties();
        settings.setPlatform("unsupported");
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                new JdbcSchemaInitializer(
                                                mock(DataSource.class),
                                                settings,
                                                new DefaultResourceLoader())
                                        .initialize());
        assertTrue(error.getMessage().contains("agent.store.jdbc.schema-locations"));
    }
}
