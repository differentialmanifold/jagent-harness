package io.github.differentialmanifold.jagentharness.example.coding.tool.support;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.differentialmanifold.jagentharness.example.coding.execution.CodingContext;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorkspacePathResolverTest {

    @TempDir Path workspaceRoot;

    @Test
    void resolvesBackslashInputAndReturnsForwardSlashPaths() {
        WorkspacePathResolver resolver = new WorkspacePathResolver();
        CodingContext context = new CodingContext(workspaceRoot);

        Path resolved = resolver.resolve(context, "src\\test\\Practice.java");

        assertEquals(
                workspaceRoot.resolve("src/test/Practice.java").toAbsolutePath().normalize(),
                resolved);
        assertEquals("src/test/Practice.java", resolver.relative(context, resolved));
    }
}
