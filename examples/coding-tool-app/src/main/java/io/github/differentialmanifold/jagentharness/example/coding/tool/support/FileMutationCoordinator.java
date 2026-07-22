package io.github.differentialmanifold.jagentharness.example.coding.tool.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.DosFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes cooperating file mutations by canonical path within this JVM.
 */
public final class FileMutationCoordinator {

    private static final int MOVE_ATTEMPTS = 4;
    private static final int PATH_STABILITY_ATTEMPTS = 4;
    private static final int UNCONDITIONAL_WRITE_ATTEMPTS = 2;
    private static final long MOVE_RETRY_BASE_DELAY_MILLIS = 25L;
    private static final boolean WINDOWS = isWindows();
    private static final Map<Path, LockEntry> LOCKS = new HashMap<Path, LockEntry>();

    private FileMutationCoordinator() {
    }

    public static LockHandle acquire(Path path) throws IOException, InterruptedException {
        Path requestedPath = path.toAbsolutePath().normalize();
        Path lastResolvedPath = null;
        for (int attempt = 0; attempt < PATH_STABILITY_ATTEMPTS; attempt++) {
            Path key = canonicalPath(requestedPath);
            lastResolvedPath = key;
            LockHandle handle = acquireCanonicalKey(key);
            boolean keep = false;
            try {
                Path confirmed = canonicalPath(requestedPath);
                if (key.equals(confirmed)) {
                    keep = true;
                    return handle;
                }
                lastResolvedPath = confirmed;
            } finally {
                if (!keep) {
                    handle.close();
                }
            }
        }
        throw new IOException("Path kept changing while acquiring its mutation lock: " + lastResolvedPath);
    }

    private static LockHandle acquireCanonicalKey(Path key) throws InterruptedException {
        LockEntry entry;
        synchronized (LOCKS) {
            entry = LOCKS.get(key);
            if (entry == null) {
                entry = new LockEntry();
                LOCKS.put(key, entry);
            }
            entry.references++;
        }

        try {
            entry.lock.lockInterruptibly();
            return new LockHandle(key, entry);
        } catch (InterruptedException e) {
            releaseReference(key, entry);
            throw e;
        }
    }

    public static Path canonicalPath(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        if (Files.exists(absolute)) {
            return absolute.toRealPath();
        }

        List<Path> missing = new LinkedList<Path>();
        Path existing = absolute;
        while (existing != null && !Files.exists(existing)) {
            if (existing.getFileName() != null) {
                missing.add(0, existing.getFileName());
            }
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }
        Path canonical = existing.toRealPath();
        for (Path part : missing) {
            canonical = canonical.resolve(part);
        }
        return canonical.normalize();
    }

    public static void writeAtomically(Path target, byte[] bytes) throws IOException {
        for (int attempt = 0; attempt < UNCONDITIONAL_WRITE_ATTEMPTS; attempt++) {
            if (writeAtomicallyIfUnchanged(target, null, bytes)) {
                return;
            }
        }
        throw new ConcurrentFileMutationException(target);
    }

    public static boolean writeAtomicallyIfUnchanged(Path target,
                                                     byte[] expected,
                                                     byte[] bytes) throws IOException {
        Path normalizedTarget = target.toAbsolutePath().normalize();
        Path parent = normalizedTarget.getParent();
        if (parent == null) {
            throw new IOException("Cannot determine parent directory for " + target);
        }
        requireStableCanonicalTarget(normalizedTarget);
        TargetSnapshot initial = TargetSnapshot.capture(normalizedTarget);
        if (initial.exists && !initial.regularFile) {
            throw new IOException("Target is not a regular file: " + normalizedTarget);
        }
        if (expected != null && (!initial.exists || expected.length != initial.size)) {
            return false;
        }
        if (initial.linkCount != null && initial.linkCount > 1L) {
            throw new HardLinkException(normalizedTarget, initial.linkCount);
        }

        Path temporary;
        try {
            temporary = createTemporarySibling(normalizedTarget, initial.exists);
        } catch (NoSuchFileException e) {
            return false;
        }
        Path canonicalTemporary = temporary.toRealPath();
        if (!Objects.equals(parent, canonicalTemporary.getParent())) {
            cleanupTemporary(canonicalTemporary);
            throw new PathChangedException(normalizedTarget, canonicalPath(normalizedTarget));
        }
        temporary = canonicalTemporary;
        boolean moved = false;
        try {
            try (FileChannel channel = FileChannel.open(
                    temporary,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            if (initial.metadata != null) {
                initial.metadata.applyTo(temporary);
            }
            TargetSnapshot current = TargetSnapshot.capture(normalizedTarget);
            if (!initial.sameFileState(current)) {
                return false;
            }
            if (expected != null) {
                if (!contentEquals(normalizedTarget, expected)
                        || !initial.sameFileState(TargetSnapshot.capture(normalizedTarget))) {
                    return false;
                }
            }
            requireStableCanonicalTarget(normalizedTarget);
            moveReplacing(temporary, normalizedTarget);
            moved = true;
            return true;
        } finally {
            if (!moved) {
                cleanupTemporary(temporary);
            }
        }
    }

    private static boolean contentEquals(Path target, byte[] expected) throws IOException {
        if (Files.size(target) != expected.length) {
            return false;
        }
        byte[] buffer = new byte[64 * 1024];
        int expectedOffset = 0;
        try (InputStream input = Files.newInputStream(target)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                if (read > expected.length - expectedOffset) {
                    return false;
                }
                for (int index = 0; index < read; index++) {
                    if (buffer[index] != expected[expectedOffset + index]) {
                        return false;
                    }
                }
                expectedOffset += read;
            }
        }
        return expectedOffset == expected.length;
    }

    private static void requireStableCanonicalTarget(Path target) throws IOException {
        Path resolved = canonicalPath(target);
        if (!target.equals(resolved)) {
            throw new PathChangedException(target, resolved);
        }
    }

    private static void cleanupTemporary(Path temporary) {
        try {
            DosFileAttributeView dosView = Files.getFileAttributeView(
                    temporary,
                    DosFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (dosView != null) {
                try {
                    dosView.setReadOnly(false);
                } catch (UnsupportedOperationException ignored) {
                    // Continue with the provider's other permission model.
                }
            }
            PosixFileAttributeView posixView = Files.getFileAttributeView(
                    temporary,
                    PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (posixView != null) {
                try {
                    Set<PosixFilePermission> permissions = new HashSet<PosixFilePermission>(
                            posixView.readAttributes().permissions());
                    permissions.add(PosixFilePermission.OWNER_WRITE);
                    posixView.setPermissions(permissions);
                } catch (UnsupportedOperationException ignored) {
                    // Continue and let deleteIfExists decide whether cleanup is possible.
                }
            }
            Files.deleteIfExists(temporary);
        } catch (IOException | RuntimeException ignored) {
            try {
                temporary.toFile().deleteOnExit();
            } catch (RuntimeException ignoredAgain) {
                // Cleanup is best effort and must not hide the original mutation outcome.
            }
        }
    }

    private static Path createTemporarySibling(Path target, boolean copySource) throws IOException {
        Path parent = target.getParent();
        for (int attempt = 0; attempt < 10; attempt++) {
            Path candidate = parent.resolve(".jagent-" + UUID.randomUUID().toString() + ".tmp");
            try {
                if (copySource) {
                    Files.copy(target, candidate, StandardCopyOption.COPY_ATTRIBUTES);
                } else {
                    Files.createFile(candidate);
                }
                return candidate;
            } catch (FileAlreadyExistsException ignored) {
                // A UUID collision is extremely unlikely, but retry without replacing another file.
            }
        }
        throw new IOException("Could not reserve a temporary file beside " + target);
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            moveReplacingWithRetry(source, target, true);
        } catch (AtomicMoveNotSupportedException e) {
            moveReplacingWithRetry(source, target, false);
        }
    }

    private static void moveReplacingWithRetry(Path source,
                                               Path target,
                                               boolean atomic) throws IOException {
        FileSystemException lastBusyFailure = null;
        for (int attempt = 0; attempt < MOVE_ATTEMPTS; attempt++) {
            try {
                if (atomic) {
                    Files.move(
                            source,
                            target,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } else {
                    Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
                }
                return;
            } catch (AtomicMoveNotSupportedException e) {
                throw e;
            } catch (AccessDeniedException e) {
                lastBusyFailure = e;
            } catch (FileSystemException e) {
                if (!WINDOWS && !isLikelyBusy(e)) {
                    throw e;
                }
                lastBusyFailure = e;
            }
            if (attempt + 1 < MOVE_ATTEMPTS) {
                pauseBeforeMoveRetry(attempt);
            }
        }
        throw new FileBusyException(target, lastBusyFailure);
    }

    private static boolean isLikelyBusy(FileSystemException failure) {
        String reason = failure.getReason();
        String message = reason == null ? failure.getMessage() : reason;
        if (message == null) {
            return false;
        }
        String normalized = message.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("busy")
                || normalized.contains("in use")
                || normalized.contains("used by another process")
                || normalized.contains("sharing violation")
                || normalized.contains("access is denied");
    }

    private static boolean isWindows() {
        try {
            return System.getProperty("os.name", "")
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains("win");
        } catch (SecurityException ignored) {
            return false;
        }
    }

    private static void pauseBeforeMoveRetry(int attempt) throws InterruptedIOException {
        try {
            Thread.sleep(MOVE_RETRY_BASE_DELAY_MILLIS * (attempt + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            InterruptedIOException failure = new InterruptedIOException(
                    "Interrupted while retrying an atomic file replacement");
            failure.initCause(e);
            throw failure;
        }
    }

    private static void releaseReference(Path key, LockEntry entry) {
        synchronized (LOCKS) {
            entry.references--;
            if (entry.references == 0) {
                LOCKS.remove(key, entry);
            }
        }
    }

    private static final class LockEntry {
        private final ReentrantLock lock = new ReentrantLock();
        private int references;
    }

    public static final class LockHandle implements AutoCloseable {
        private final Path key;
        private final LockEntry entry;
        private boolean closed;

        private LockHandle(Path key, LockEntry entry) {
            this.key = key;
            this.entry = entry;
        }

        public Path getCanonicalPath() {
            return key;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            entry.lock.unlock();
            releaseReference(key, entry);
        }
    }

    public static final class FileBusyException extends IOException {
        private final Path target;

        private FileBusyException(Path target, IOException cause) {
            super("File could not be replaced after short retries; it may be open or access may be denied: "
                    + target, cause);
            this.target = target;
        }

        public Path getTarget() {
            return target;
        }
    }

    public static final class ConcurrentFileMutationException extends IOException {
        private final Path target;

        private ConcurrentFileMutationException(Path target) {
            super("File changed repeatedly while it was being replaced: " + target);
            this.target = target;
        }

        public Path getTarget() {
            return target;
        }
    }

    public static final class HardLinkException extends IOException {
        private final Path target;
        private final long linkCount;

        private HardLinkException(Path target, long linkCount) {
            super("Atomic replacement would detach this path from its " + linkCount
                    + " hard links: " + target);
            this.target = target;
            this.linkCount = linkCount;
        }

        public Path getTarget() {
            return target;
        }

        public long getLinkCount() {
            return linkCount;
        }
    }

    public static final class PathChangedException extends IOException {
        private final Path approvedTarget;
        private final Path currentTarget;

        private PathChangedException(Path approvedTarget, Path currentTarget) {
            super("The target path resolved to a different location while the file operation was running: "
                    + currentTarget);
            this.approvedTarget = approvedTarget;
            this.currentTarget = currentTarget;
        }

        public Path getApprovedTarget() {
            return approvedTarget;
        }

        public Path getCurrentTarget() {
            return currentTarget;
        }
    }

    private static final class TargetSnapshot {
        private final boolean exists;
        private final boolean regularFile;
        private final long size;
        private final Object fileKey;
        private final FileTime creationTime;
        private final FileTime lastModifiedTime;
        private final Long linkCount;
        private final FileMetadata metadata;

        private TargetSnapshot(boolean exists,
                               boolean regularFile,
                               long size,
                               Object fileKey,
                               FileTime creationTime,
                               FileTime lastModifiedTime,
                               Long linkCount,
                               FileMetadata metadata) {
            this.exists = exists;
            this.regularFile = regularFile;
            this.size = size;
            this.fileKey = fileKey;
            this.creationTime = creationTime;
            this.lastModifiedTime = lastModifiedTime;
            this.linkCount = linkCount;
            this.metadata = metadata;
        }

        private static TargetSnapshot capture(Path target) throws IOException {
            try {
                BasicFileAttributes attributes = Files.readAttributes(
                        target,
                        BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isRegularFile()) {
                    return new TargetSnapshot(
                            true,
                            false,
                            attributes.size(),
                            attributes.fileKey(),
                            attributes.creationTime(),
                            attributes.lastModifiedTime(),
                            null,
                            null);
                }
                FileMetadata metadata = FileMetadata.capture(target);
                Long linkCount = readLinkCount(target);
                return new TargetSnapshot(
                        true,
                        true,
                        attributes.size(),
                        attributes.fileKey(),
                        attributes.creationTime(),
                        attributes.lastModifiedTime(),
                        linkCount,
                        metadata);
            } catch (NoSuchFileException e) {
                return new TargetSnapshot(false, false, 0L, null, null, null, null, null);
            }
        }

        private static Long readLinkCount(Path target) throws IOException {
            try {
                Object value = Files.getAttribute(
                        target,
                        "unix:nlink",
                        LinkOption.NOFOLLOW_LINKS);
                return value instanceof Number ? ((Number) value).longValue() : null;
            } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
                return null;
            }
        }

        private boolean sameFileState(TargetSnapshot other) {
            if (exists != other.exists) {
                return false;
            }
            if (!exists) {
                return true;
            }
            return regularFile == other.regularFile
                    && size == other.size
                    && Objects.equals(fileKey, other.fileKey)
                    && Objects.equals(creationTime, other.creationTime)
                    && Objects.equals(lastModifiedTime, other.lastModifiedTime)
                    && Objects.equals(linkCount, other.linkCount)
                    && (metadata == null
                    ? other.metadata == null
                    : metadata.equivalentTo(other.metadata));
        }
    }

    private static final class FileMetadata {
        private UserPrincipal owner;
        private GroupPrincipal group;
        private Set<PosixFilePermission> posixPermissions;
        private List<AclEntry> acl;
        private Integer unixMode;
        private Boolean dosArchive;
        private Boolean dosHidden;
        private Boolean dosReadOnly;
        private Boolean dosSystem;
        private Map<String, byte[]> userAttributes;

        private static FileMetadata capture(Path source) throws IOException {
            FileMetadata metadata = new FileMetadata();
            LinkOption[] noFollow = new LinkOption[]{LinkOption.NOFOLLOW_LINKS};

            FileOwnerAttributeView ownerView = Files.getFileAttributeView(
                    source,
                    FileOwnerAttributeView.class,
                    noFollow);
            if (ownerView != null) {
                try {
                    metadata.owner = ownerView.getOwner();
                } catch (UnsupportedOperationException ignored) {
                    // This provider does not expose ownership.
                }
            }

            try {
                PosixFileAttributes attributes = Files.readAttributes(
                        source,
                        PosixFileAttributes.class,
                        noFollow);
                metadata.group = attributes.group();
                metadata.posixPermissions = new HashSet<PosixFilePermission>(attributes.permissions());
            } catch (UnsupportedOperationException ignored) {
                // Windows and other non-POSIX providers use ACL/DOS views instead.
            }

            AclFileAttributeView aclView = Files.getFileAttributeView(
                    source,
                    AclFileAttributeView.class,
                    noFollow);
            if (aclView != null) {
                try {
                    metadata.acl = new ArrayList<AclEntry>(aclView.getAcl());
                } catch (UnsupportedOperationException ignored) {
                    // ACLs are not available from this provider.
                }
            }

            try {
                Object mode = Files.getAttribute(source, "unix:mode", noFollow);
                if (mode instanceof Number) {
                    metadata.unixMode = ((Number) mode).intValue();
                }
            } catch (UnsupportedOperationException | IllegalArgumentException ignored) {
                // The unix view is optional and is mainly used to retain special mode bits.
            }

            try {
                DosFileAttributes attributes = Files.readAttributes(
                        source,
                        DosFileAttributes.class,
                        noFollow);
                metadata.dosArchive = attributes.isArchive();
                metadata.dosHidden = attributes.isHidden();
                metadata.dosReadOnly = attributes.isReadOnly();
                metadata.dosSystem = attributes.isSystem();
            } catch (UnsupportedOperationException ignored) {
                // Non-DOS providers do not expose these flags.
            }

            UserDefinedFileAttributeView userView = Files.getFileAttributeView(
                    source,
                    UserDefinedFileAttributeView.class,
                    noFollow);
            if (userView != null) {
                try {
                    metadata.userAttributes = readUserAttributes(userView);
                } catch (UnsupportedOperationException ignored) {
                    // Extended user attributes are not available from this provider.
                }
            }
            return metadata;
        }

        private void applyTo(Path target) throws IOException {
            LinkOption[] noFollow = new LinkOption[]{LinkOption.NOFOLLOW_LINKS};
            if (owner != null) {
                FileOwnerAttributeView ownerView = Files.getFileAttributeView(
                        target,
                        FileOwnerAttributeView.class,
                        noFollow);
                if (ownerView == null) {
                    throw new IOException("Cannot preserve file owner for " + target);
                }
                ownerView.setOwner(owner);
            }
            if (group != null || posixPermissions != null) {
                PosixFileAttributeView posixView = Files.getFileAttributeView(
                        target,
                        PosixFileAttributeView.class,
                        noFollow);
                if (posixView == null) {
                    throw new IOException("Cannot preserve POSIX metadata for " + target);
                }
                if (group != null) {
                    posixView.setGroup(group);
                }
                if (posixPermissions != null) {
                    posixView.setPermissions(posixPermissions);
                }
            }
            if (unixMode != null) {
                Files.setAttribute(target, "unix:mode", unixMode, noFollow);
            }
            if (acl != null) {
                AclFileAttributeView aclView = Files.getFileAttributeView(
                        target,
                        AclFileAttributeView.class,
                        noFollow);
                if (aclView == null) {
                    throw new IOException("Cannot preserve ACLs for " + target);
                }
                aclView.setAcl(acl);
            }
            if (userAttributes != null) {
                UserDefinedFileAttributeView userView = Files.getFileAttributeView(
                        target,
                        UserDefinedFileAttributeView.class,
                        noFollow);
                if (userView == null) {
                    throw new IOException("Cannot preserve extended file attributes for " + target);
                }
                writeUserAttributes(userView, userAttributes);
            }
            if (dosArchive != null) {
                DosFileAttributeView dosView = Files.getFileAttributeView(
                        target,
                        DosFileAttributeView.class,
                        noFollow);
                if (dosView == null) {
                    throw new IOException("Cannot preserve DOS file attributes for " + target);
                }
                dosView.setArchive(dosArchive);
                dosView.setHidden(dosHidden);
                dosView.setSystem(dosSystem);
                dosView.setReadOnly(dosReadOnly);
            }
        }

        private boolean equivalentTo(FileMetadata other) {
            return other != null
                    && Objects.equals(owner, other.owner)
                    && Objects.equals(group, other.group)
                    && Objects.equals(posixPermissions, other.posixPermissions)
                    && Objects.equals(acl, other.acl)
                    && Objects.equals(unixMode, other.unixMode)
                    && Objects.equals(dosArchive, other.dosArchive)
                    && Objects.equals(dosHidden, other.dosHidden)
                    && Objects.equals(dosReadOnly, other.dosReadOnly)
                    && Objects.equals(dosSystem, other.dosSystem)
                    && userAttributesEqual(userAttributes, other.userAttributes);
        }

        private static boolean userAttributesEqual(Map<String, byte[]> left,
                                                   Map<String, byte[]> right) {
            if (left == right) {
                return true;
            }
            if (left == null || right == null || !left.keySet().equals(right.keySet())) {
                return false;
            }
            for (String name : left.keySet()) {
                if (!Arrays.equals(left.get(name), right.get(name))) {
                    return false;
                }
            }
            return true;
        }

        private static Map<String, byte[]> readUserAttributes(UserDefinedFileAttributeView view)
                throws IOException {
            Map<String, byte[]> attributes = new LinkedHashMap<String, byte[]>();
            for (String name : view.list()) {
                int size = view.size(name);
                ByteBuffer buffer = ByteBuffer.allocate(size);
                int read = view.read(name, buffer);
                if (read != size) {
                    throw new IOException("Could not read complete extended attribute: " + name);
                }
                buffer.flip();
                byte[] value = new byte[buffer.remaining()];
                buffer.get(value);
                attributes.put(name, value);
            }
            return attributes;
        }

        private static void writeUserAttributes(UserDefinedFileAttributeView view,
                                                Map<String, byte[]> attributes) throws IOException {
            for (String existing : view.list()) {
                if (!attributes.containsKey(existing)) {
                    view.delete(existing);
                }
            }
            for (Map.Entry<String, byte[]> attribute : attributes.entrySet()) {
                ByteBuffer value = ByteBuffer.wrap(attribute.getValue());
                int written = view.write(attribute.getKey(), value);
                if (written != attribute.getValue().length) {
                    throw new IOException(
                            "Could not preserve complete extended attribute: " + attribute.getKey());
                }
            }
        }
    }
}
