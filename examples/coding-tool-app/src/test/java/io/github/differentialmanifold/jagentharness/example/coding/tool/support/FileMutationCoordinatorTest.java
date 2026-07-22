package io.github.differentialmanifold.jagentharness.example.coding.tool.support;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileMutationCoordinatorTest {

    @TempDir
    Path directory;

    @Test
    void serializesMutationsForTheSameCanonicalPath() throws Exception {
        Path file = directory.resolve("shared.txt");
        Files.write(file, "old\n".getBytes(StandardCharsets.UTF_8));
        CountDownLatch firstAcquired = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch secondAcquired = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> {
                try (FileMutationCoordinator.LockHandle ignored = FileMutationCoordinator.acquire(file)) {
                    firstAcquired.countDown();
                    releaseFirst.await();
                }
                return null;
            });
            assertTrue(firstAcquired.await(5, TimeUnit.SECONDS));

            Future<?> second = executor.submit(() -> {
                secondStarted.countDown();
                try (FileMutationCoordinator.LockHandle ignored = FileMutationCoordinator.acquire(file)) {
                    secondAcquired.countDown();
                }
                return null;
            });
            assertTrue(secondStarted.await(5, TimeUnit.SECONDS));
            assertFalse(secondAcquired.await(100, TimeUnit.MILLISECONDS));

            releaseFirst.countDown();
            assertTrue(secondAcquired.await(5, TimeUnit.SECONDS));
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void commitsOnlyWhenTheExpectedSnapshotStillMatches() throws Exception {
        Path file = directory.resolve("conditional.txt");
        byte[] original = "original\n".getBytes(StandardCharsets.UTF_8);
        byte[] external = "external\n".getBytes(StandardCharsets.UTF_8);
        byte[] replacement = "replacement\n".getBytes(StandardCharsets.UTF_8);
        Files.write(file, original);
        Files.write(file, external);

        assertFalse(FileMutationCoordinator.writeAtomicallyIfUnchanged(
                canonical(file), original, replacement));
        assertArrayEquals(external, Files.readAllBytes(file));

        assertTrue(FileMutationCoordinator.writeAtomicallyIfUnchanged(
                canonical(file), external, replacement));
        assertArrayEquals(replacement, Files.readAllBytes(file));
    }

    @Test
    void reResolvesTheCanonicalPathAfterWaitingForItsLock() throws Exception {
        Path parent = directory.resolve("locked-parent");
        Files.createDirectory(parent);
        Path requested = parent.resolve("target.txt");
        Path outside = Files.createTempDirectory("jagent-lock-swap");
        Path probe = directory.resolve("symlink-probe");
        try {
            Files.createSymbolicLink(probe, outside);
            Files.delete(probe);
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Symbolic links are unavailable");
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        FileMutationCoordinator.LockHandle first = FileMutationCoordinator.acquire(requested);
        try {
            CountDownLatch started = new CountDownLatch(1);
            Future<Path> second = executor.submit(() -> {
                started.countDown();
                try (FileMutationCoordinator.LockHandle lock = FileMutationCoordinator.acquire(requested)) {
                    return lock.getCanonicalPath();
                }
            });
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));

            Files.move(parent, directory.resolve("original-parent"));
            Files.createSymbolicLink(parent, outside);
            first.close();

            assertEquals(outside.toRealPath().resolve("target.txt"), second.get(5, TimeUnit.SECONDS));
        } finally {
            first.close();
            executor.shutdownNow();
        }
    }

    @Test
    void refusesToDetachAnExistingHardLink() throws Exception {
        Path file = directory.resolve("hard-linked.txt");
        Path alias = directory.resolve("hard-linked-alias.txt");
        Files.write(file, "old\n".getBytes(StandardCharsets.UTF_8));
        try {
            Files.createLink(alias, file);
            Object linkCount = Files.getAttribute(file, "unix:nlink");
            org.junit.jupiter.api.Assumptions.assumeTrue(((Number) linkCount).longValue() > 1L);
        } catch (Exception e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "Hard-link count is unavailable");
        }

        assertThrows(
                FileMutationCoordinator.HardLinkException.class,
                () -> FileMutationCoordinator.writeAtomically(
                        canonical(file),
                        "new\n".getBytes(StandardCharsets.UTF_8)));

        assertArrayEquals("old\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(file));
        assertArrayEquals("old\n".getBytes(StandardCharsets.UTF_8), Files.readAllBytes(alias));
    }

    @Test
    void preservesExistingPosixOwnerGroupAndMode() throws Exception {
        Path file = directory.resolve("metadata.txt");
        Files.write(file, "old\n".getBytes(StandardCharsets.UTF_8));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.getFileStore(file).supportsFileAttributeView("posix"));
        Set<PosixFilePermission> permissions = EnumSet.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE,
                PosixFilePermission.GROUP_READ);
        Files.setPosixFilePermissions(file, permissions);
        PosixFileAttributes before = Files.readAttributes(file, PosixFileAttributes.class);

        FileMutationCoordinator.writeAtomically(
                canonical(file),
                "new\n".getBytes(StandardCharsets.UTF_8));

        PosixFileAttributes after = Files.readAttributes(file, PosixFileAttributes.class);
        assertEquals(before.owner(), after.owner());
        assertEquals(before.group(), after.group());
        assertEquals(permissions, after.permissions());
    }

    @Test
    void newFilesUseNormalCreatePermissionsInsteadOfTempFilePermissions() throws Exception {
        Path baseline = directory.resolve("baseline.txt");
        Files.createFile(baseline);
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.getFileStore(baseline).supportsFileAttributeView("posix"));
        Set<PosixFilePermission> expectedPermissions = Files.getPosixFilePermissions(baseline);
        Path created = directory.resolve("created.txt");

        FileMutationCoordinator.writeAtomically(
                canonical(created),
                "new\n".getBytes(StandardCharsets.UTF_8));

        assertEquals(expectedPermissions, Files.getPosixFilePermissions(created));
    }

    @Test
    void preservesUserDefinedAttributesWhenSupported() throws Exception {
        Path file = directory.resolve("extended-attributes.txt");
        Files.write(file, "old\n".getBytes(StandardCharsets.UTF_8));
        UserDefinedFileAttributeView view = Files.getFileAttributeView(
                file,
                UserDefinedFileAttributeView.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(view != null);
        byte[] expected = "metadata-value".getBytes(StandardCharsets.UTF_8);
        try {
            view.write("jagent-harness-test", ByteBuffer.wrap(expected));
        } catch (UnsupportedOperationException e) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false, "User attributes are unavailable");
        }

        FileMutationCoordinator.writeAtomically(
                canonical(file),
                "new\n".getBytes(StandardCharsets.UTF_8));

        UserDefinedFileAttributeView updatedView = Files.getFileAttributeView(
                file,
                UserDefinedFileAttributeView.class);
        ByteBuffer value = ByteBuffer.allocate(updatedView.size("jagent-harness-test"));
        updatedView.read("jagent-harness-test", value);
        value.flip();
        byte[] actual = new byte[value.remaining()];
        value.get(actual);
        assertArrayEquals(expected, actual);
    }

    @Test
    void preservesWindowsAclOwnerAndDosFlagsWhenSupported() throws Exception {
        Path file = directory.resolve("windows-metadata.txt");
        Files.write(file, "old\n".getBytes(StandardCharsets.UTF_8));
        AclFileAttributeView aclView = Files.getFileAttributeView(file, AclFileAttributeView.class);
        DosFileAttributeView dosView = Files.getFileAttributeView(file, DosFileAttributeView.class);
        org.junit.jupiter.api.Assumptions.assumeTrue(aclView != null && dosView != null);
        List<AclEntry> acl = new ArrayList<AclEntry>(aclView.getAcl());
        UserPrincipal owner = aclView.getOwner();
        dosView.setArchive(true);
        dosView.setHidden(true);

        FileMutationCoordinator.writeAtomically(
                canonical(file),
                "new\n".getBytes(StandardCharsets.UTF_8));

        AclFileAttributeView updatedAclView = Files.getFileAttributeView(file, AclFileAttributeView.class);
        DosFileAttributes dos = Files.readAttributes(file, DosFileAttributes.class);
        assertEquals(owner, updatedAclView.getOwner());
        assertEquals(acl, updatedAclView.getAcl());
        assertTrue(dos.isArchive());
        assertTrue(dos.isHidden());
    }

    private Path canonical(Path path) throws Exception {
        return FileMutationCoordinator.canonicalPath(path);
    }
}
