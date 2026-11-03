package io.github.differentialmanifold.jagentharness.store.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.skill.*;
import java.nio.file.Path;
import java.util.Collections;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

class JdbcSkillActivationStoreTest {
    @TempDir Path directory;

    @Test
    void sqlitePersistsCompleteIdempotentReceiptsAcrossStoreRecreation() {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + directory.resolve("activations.db"));
        verifyPersistenceAndIsolation(source);
    }

    @Test
    void h2PersistsCompleteIdempotentReceiptsAcrossStoreRecreation() {
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:skill-activations;DB_CLOSE_DELAY=-1");
        verifyPersistenceAndIsolation(source);
    }

    private void verifyPersistenceAndIsolation(DataSource source) {
        new JdbcSchemaInitializer(source).initialize();
        SkillActivationStore store = store(source, "app1");
        SkillActivation receipt =
                new SkillActivation(
                        "skills/refund/SKILL.md",
                        "project:p1",
                        SkillActivation.digest("v1"),
                        Collections.singletonList(
                                new SkillToolBinding(
                                        "merchant", "get_order", "merchant__get_order")));
        store.activate("session", "project:p1", receipt);
        store.activate("session", "project:p1", receipt);
        // Re-running bundled schema initialization must preserve existing sessions and activations.
        new JdbcSchemaInitializer(source).initialize();
        SkillActivationStore restored = store(source, "app1");
        assertEquals(1, restored.find("session", "project:p1").size());
        SkillActivation saved = restored.find("session", "project:p1").get(0);
        assertEquals(receipt.activationId(), saved.activationId());
        assertEquals("merchant__get_order", saved.getTools().get(0).getModelName());
        assertTrue(restored.find("other-session", "project:p1").isEmpty());
        assertTrue(restored.find("session", "project:p2").isEmpty());
        assertTrue(restored.find("session", "agent:p1").isEmpty());
        assertTrue(store(source, "app2").find("session", "project:p1").isEmpty());
        restored.activate(
                "session",
                "project:p1",
                new SkillActivation(
                        "skills/invoice/SKILL.md",
                        "global:",
                        SkillActivation.digest("v2"),
                        Collections.singletonList(
                                new SkillToolBinding("billing", "invoice", "billing__invoice"))));
        assertEquals(2, store.find("session", "project:p1").size());
        java.util.stream.IntStream.range(0, 16)
                .parallel()
                .forEach(
                        index ->
                                store(source, "concurrent-app")
                                        .activate(
                                                "session",
                                                "global",
                                                new SkillActivation(
                                                        "skills/business-"
                                                                + (index % 2)
                                                                + "/SKILL.md",
                                                        "global:",
                                                        SkillActivation.digest("v1"),
                                                        receipt.getTools())));
        assertEquals(2, store(source, "concurrent-app").find("session", "global").size());
    }

    private SkillActivationStore store(DataSource source, String application) {
        JdbcStoreProperties properties = new JdbcStoreProperties();
        properties.setApplicationId(application);
        return new JdbcSkillActivationStore(
                new JdbcTemplate(source), new ObjectMapper(), properties);
    }
}
