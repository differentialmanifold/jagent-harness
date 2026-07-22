package io.github.differentialmanifold.jagentharness.example.coding.tool;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.differentialmanifold.jagentharness.core.agent.StopSignal;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalDecision;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalMode;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalRejectedException;
import io.github.differentialmanifold.jagentharness.core.tool.ToolApprovalRequest;
import io.github.differentialmanifold.jagentharness.core.tool.ToolContext;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.WorkspacePathResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

class WriteToolTest {

    @TempDir
    Path workspaceRoot;

    @Test
    void writesInsideWorkspaceWithoutApproval() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<ToolApprovalRequest> approval = new AtomicReference<ToolApprovalRequest>();
        WriteTool tool = new WriteTool(objectMapper, new WorkspacePathResolver());

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "note.txt");
        arguments.put("content", "hello\n");

        tool.execute(approvalContext(approval, ToolApprovalDecision.approved()), arguments);

        assertEquals("hello\n", new String(Files.readAllBytes(workspaceRoot.resolve("note.txt")), StandardCharsets.UTF_8));
        assertEquals(null, approval.get());
    }

    @Test
    void asksBeforeWritingOutsideWorkspace() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<ToolApprovalRequest> approval = new AtomicReference<ToolApprovalRequest>();
        WriteTool tool = new WriteTool(objectMapper, new WorkspacePathResolver());
        Path outside = Files.createTempDirectory("jagent-outside").resolve("outside.txt");

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", outside.toString());
        arguments.put("content", "outside\n");

        tool.execute(approvalContext(approval, ToolApprovalDecision.approved()), arguments);

        assertEquals("write", approval.get().getAction());
        assertEquals(outside.toAbsolutePath().normalize().toRealPath().toString(), approval.get().getTarget());
        assertEquals("write", approval.get().getToolName());
        assertEquals("outside\n", new String(Files.readAllBytes(outside), StandardCharsets.UTF_8));
    }

    @Test
    void doesNotWriteOutsideWorkspaceWhenApprovalIsDenied() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        AtomicReference<ToolApprovalRequest> approval = new AtomicReference<ToolApprovalRequest>();
        WriteTool tool = new WriteTool(objectMapper, new WorkspacePathResolver());
        Path outside = Files.createTempDirectory("jagent-outside").resolve("denied.txt");

        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", outside.toString());
        arguments.put("content", "outside\n");

        assertThrows(
                ToolApprovalRejectedException.class,
                () -> tool.execute(approvalContext(approval, ToolApprovalDecision.denied("denied")), arguments));

        assertTrue(approval.get().getMessage().contains("outside"));
        assertFalse(Files.exists(outside));
    }

    @Test
    void rejectsNulAndInvalidUnicodeContent() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        WriteTool tool = new WriteTool(objectMapper, new WorkspacePathResolver());

        ObjectNode nul = objectMapper.createObjectNode();
        nul.put("path", "invalid/nested/nul.txt");
        nul.put("content", "bad\0content");
        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(approvalContext(new AtomicReference<ToolApprovalRequest>(),
                        ToolApprovalDecision.approved()), nul));
        assertFalse(Files.exists(workspaceRoot.resolve("invalid")));

        ObjectNode surrogate = objectMapper.createObjectNode();
        surrogate.put("path", "surrogate.txt");
        surrogate.put("content", "\uD800");
        assertThrows(IllegalArgumentException.class,
                () -> tool.execute(approvalContext(new AtomicReference<ToolApprovalRequest>(),
                        ToolApprovalDecision.approved()), surrogate));
        assertFalse(Files.exists(workspaceRoot.resolve("surrogate.txt")));
    }

    @Test
    void rechecksApprovalWhenAParentBecomesAnOutsideSymlink() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        Path outsideDirectory = Files.createTempDirectory("jagent-symlink-swap");
        Path swappedParent = workspaceRoot.resolve("swapped-parent");
        Path probe = workspaceRoot.resolve("symlink-probe");
        try {
            Files.createSymbolicLink(probe, outsideDirectory);
            Files.delete(probe);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }
        WriteTool tool = new WriteTool(objectMapper, new WorkspacePathResolver()) {
            @Override
            void createParentDirectories(Path parent) throws IOException {
                Files.createSymbolicLink(swappedParent, outsideDirectory);
            }
        };
        AtomicReference<ToolApprovalRequest> approval = new AtomicReference<ToolApprovalRequest>();
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "swapped-parent/escaped.txt");
        arguments.put("content", "outside\n");

        assertThrows(
                ToolApprovalRejectedException.class,
                () -> tool.execute(
                        approvalContext(approval, ToolApprovalDecision.denied("denied")),
                        arguments));

        assertEquals(outsideDirectory.toRealPath().resolve("escaped.txt").toString(), approval.get().getTarget());
        assertFalse(Files.exists(outsideDirectory.resolve("escaped.txt")));
    }

    @Test
    void requiresApprovalForWorkspaceSymlinkThatTargetsOutside() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();
        WriteTool tool = new WriteTool(objectMapper, new WorkspacePathResolver());
        Path outsideDirectory = Files.createTempDirectory("jagent-symlink-outside");
        Path link = workspaceRoot.resolve("linked-directory");
        try {
            Files.createSymbolicLink(link, outsideDirectory);
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Assumptions.assumeTrue(false, "Symbolic links are unavailable: " + e.getMessage());
        }
        AtomicReference<ToolApprovalRequest> approval = new AtomicReference<ToolApprovalRequest>();
        ObjectNode arguments = objectMapper.createObjectNode();
        arguments.put("path", "linked-directory/escaped.txt");
        arguments.put("content", "outside\n");

        assertThrows(
                ToolApprovalRejectedException.class,
                () -> tool.execute(
                        approvalContext(approval, ToolApprovalDecision.denied("denied")),
                        arguments));

        assertEquals("write", approval.get().getAction());
        assertEquals(outsideDirectory.toRealPath().resolve("escaped.txt").toString(), approval.get().getTarget());
        assertFalse(Files.exists(outsideDirectory.resolve("escaped.txt")));
    }

    private ToolContext approvalContext(AtomicReference<ToolApprovalRequest> request,
                                        ToolApprovalDecision decision) {
        return new ToolContext(
                "session",
                "run",
                "turn",
                null,
                workspaceRoot,
                null,
                Collections.emptyMap(),
                StopSignal.none(),
                ToolApprovalMode.ASK_FOR_APPROVAL,
                (approvalRequest, stopSignal) -> {
                    request.set(approvalRequest);
                    return decision;
                },
                "call-1",
                "write");
    }
}
