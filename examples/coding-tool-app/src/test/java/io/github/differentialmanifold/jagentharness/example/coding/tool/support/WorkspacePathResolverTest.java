package io.github.differentialmanifold.jagentharness.example.coding.tool.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;

import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacePathResolverTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void resolvesBackslashInputAndReturnsForwardSlashPaths() {
        WorkspacePathResolver resolver = new WorkspacePathResolver();
        ToolContext context = new ToolContext("session", "turn", workspaceRoot);

        Path resolved = resolver.resolve(context, "src\\test\\Practice.java");

        assertEquals(workspaceRoot.resolve("src/test/Practice.java").toAbsolutePath().normalize(), resolved);
        assertEquals("src/test/Practice.java", resolver.relative(context, resolved));
    }
}
