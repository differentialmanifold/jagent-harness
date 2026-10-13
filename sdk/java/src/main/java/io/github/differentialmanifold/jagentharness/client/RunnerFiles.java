package io.github.differentialmanifold.jagentharness.client;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;

final class RunnerFiles {
    static Lease lock(Path home) throws IOException {
        directory(home);
        FileChannel channel =
                FileChannel.open(
                        home.resolve("runner.lock"),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE);
        try {
            java.nio.channels.FileLock lock = channel.tryLock();
            if (lock == null)
                throw new IllegalStateException("This runner state directory is already in use");
            return new Lease(channel, lock);
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    static final class Lease implements Closeable {
        private final FileChannel channel;
        private final java.nio.channels.FileLock lock;

        Lease(FileChannel channel, java.nio.channels.FileLock lock) {
            this.channel = channel;
            this.lock = lock;
        }

        public void close() throws IOException {
            try {
                lock.release();
            } finally {
                channel.close();
            }
        }
    }

    static final ObjectMapper JSON =
            new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    static void directory(Path path) throws IOException {
        Files.createDirectories(path);
        try {
            Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rwx------"));
        } catch (UnsupportedOperationException ignored) {
        }
    }

    static void save(Path path, Object value) throws IOException {
        directory(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), ".write-", ".tmp");
        try {
            try {
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException ignored) {
            }
            Files.write(temp, JSON.writeValueAsBytes(value));
            try (FileChannel channel = FileChannel.open(temp, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(
                        temp,
                        path,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static String digest(byte[] bytes) {
        try {
            StringBuilder out = new StringBuilder();
            for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes))
                out.append(String.format("%02x", b & 255));
            return out.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
