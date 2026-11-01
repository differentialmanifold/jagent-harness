package io.github.differentialmanifold.jagentharness.client;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalWorkspacesTest {
    @TempDir Path temp;

    @Test
    void createsOnlyManagedProjectDirectories() throws Exception {
        Path root = temp.resolve("workspaces");
        LocalWorkspaces workspaces = new LocalWorkspaces(root);
        Path user = root.resolve("user/project_123");
        Path agent = root.resolve("agents/agent_123/project_456");
        Path resolvedUser = workspaces.resolve(user.toString());
        assertEquals(user.toRealPath(), resolvedUser);
        Path resolvedAgent = workspaces.resolve(agent.toString());
        assertEquals(agent.toRealPath(), resolvedAgent);
        assertTrue(Files.isDirectory(agent));
        assertThrows(Exception.class, () -> workspaces.resolve(temp.resolve("missing").toString()));
        assertThrows(
                Exception.class,
                () -> workspaces.resolve(root.resolve("agents/agent_123/missing").toString()));
        assertThrows(
                Exception.class,
                () -> workspaces.resolve(root.resolve("user/../../outside").toString()));
        Path existing = Files.createDirectory(temp.resolve("existing"));
        assertEquals(existing.toRealPath(), workspaces.resolve(existing.toString()));
        assertFalse(Files.exists(temp.resolve("outside")));
    }
}
