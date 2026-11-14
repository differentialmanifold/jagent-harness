package io.github.differentialmanifold.jagentharness.store.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.model.*;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class JdbcModelCatalogStoreTest {
    @TempDir Path temp;

    @Test
    void survivesRecreationWithApplicationAndRunIsolation() {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + temp.resolve("models.db"));
        new JdbcSchemaInitializer(ds).initialize();
        JdbcTemplate db = new JdbcTemplate(ds);
        JdbcStoreProperties properties = new JdbcStoreProperties();
        properties.setApplicationId("one");
        JdbcModelCatalogStore store = new JdbcModelCatalogStore(db, new ObjectMapper(), properties);
        ModelCatalogStore.Configuration config = new ModelCatalogStore.Configuration();
        config.defaultModelId = "a";
        assertTrue(store.save(config));
        assertFalse(store.save(config));
        ModelCatalogStore.Selection selected = new ModelCatalogStore.Selection();
        selected.model = new ModelProfile();
        selected.model.id = "a";
        store.select("session", "run", selected);
        JdbcModelCatalogStore restored =
                new JdbcModelCatalogStore(db, new ObjectMapper(), properties);
        assertEquals("a", restored.configuration().defaultModelId);
        assertEquals(1, restored.configuration().revision);
        assertNull(restored.selection("session", "other"));
        properties.setApplicationId("two");
        JdbcModelCatalogStore other = new JdbcModelCatalogStore(db, new ObjectMapper(), properties);
        assertNull(other.configuration());
        assertNull(other.selection("session", "run"));
    }
}
