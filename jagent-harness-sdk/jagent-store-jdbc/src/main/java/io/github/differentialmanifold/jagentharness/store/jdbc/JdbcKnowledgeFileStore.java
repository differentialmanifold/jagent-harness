package io.github.differentialmanifold.jagentharness.store.jdbc;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFile;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFilePaths;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillDescriptor;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillFileParser;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillManifest;
import io.github.differentialmanifold.jagentharness.core.prompt.SkillManifestStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

public class JdbcKnowledgeFileStore implements KnowledgeFileStore, SkillManifestStore {

    private static final String DEFAULT_CONTENT_TYPE = "text/markdown";
    private static final String DIRECTORY_CONTENT_TYPE = "inode/directory";

    private final JdbcTemplate jdbcTemplate;

    private final RowMapper<KnowledgeFile> fileMapper = (rs, rowNum) -> new KnowledgeFile(
            rs.getString("path"),
            rs.getString("node_type"),
            rs.getString("content"),
            rs.getString("content_type"),
            JdbcTimeCodec.decode(rs.getString("created_at")),
            JdbcTimeCodec.decode(rs.getString("updated_at")));

    private final RowMapper<SkillManifest> skillManifestMapper = (rs, rowNum) -> new SkillManifest(
            rs.getString("skill_key"),
            rs.getString("skill_dir_path"),
            rs.getString("skill_file_path"),
            rs.getString("name"),
            rs.getString("description"),
            JdbcTimeCodec.decode(rs.getString("updated_at")));

    public JdbcKnowledgeFileStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public KnowledgeFile readFile(String path) {
        List<KnowledgeFile> files = jdbcTemplate.query(
                "select * from knowledge_files where path = ? and node_type = ?",
                fileMapper,
                KnowledgeFilePaths.normalize(path),
                KnowledgeFile.TYPE_FILE);
        return files.isEmpty() ? null : files.get(0);
    }

    @Override
    public List<KnowledgeFile> listFiles(String prefix) {
        String normalizedPrefix = KnowledgeFilePaths.normalizePrefix(prefix);
        if (normalizedPrefix.isEmpty()) {
            return jdbcTemplate.query(
                    "select * from knowledge_files where node_type = ? order by path asc",
                    fileMapper,
                    KnowledgeFile.TYPE_FILE);
        }
        return jdbcTemplate.query(
                "select * from knowledge_files where node_type = ? and (path = ? or path like ?) order by path asc",
                fileMapper,
                KnowledgeFile.TYPE_FILE,
                normalizedPrefix,
                normalizedPrefix + "/%");
    }

    @Override
    public KnowledgeFile writeFile(String path, String content, String contentType) {
        String normalizedPath = KnowledgeFilePaths.normalize(path);
        String effectiveContentType = contentType == null || contentType.trim().isEmpty()
                ? DEFAULT_CONTENT_TYPE
                : contentType.trim();
        ensureDirectories(normalizedPath);
        upsertFile(normalizedPath, KnowledgeFile.TYPE_FILE, content == null ? "" : content, effectiveContentType);
        syncIndexes(normalizedPath, content == null ? "" : content);
        return readFile(normalizedPath);
    }

    @Override
    public void deleteFile(String path) {
        String normalizedPath = KnowledgeFilePaths.normalize(path);
        jdbcTemplate.update("delete from knowledge_files where path = ?", normalizedPath);
        deleteSkillManifest(normalizedPath);
    }

    @Override
    public List<SkillManifest> listManifests() {
        return jdbcTemplate.query("select * from skill_manifests order by skill_key asc", skillManifestMapper);
    }

    private void ensureDirectories(String path) {
        String parent = KnowledgeFilePaths.parent(path);
        if (parent.isEmpty()) {
            return;
        }
        String[] segments = parent.split("/");
        StringBuilder current = new StringBuilder();
        for (String segment : segments) {
            if (current.length() > 0) {
                current.append('/');
            }
            current.append(segment);
            upsertFile(current.toString(), KnowledgeFile.TYPE_DIRECTORY, "", DIRECTORY_CONTENT_TYPE);
        }
    }

    private void upsertFile(String path, String nodeType, String content, String contentType) {
        Instant now = Instant.now();
        boolean exists = exists("knowledge_files", "path", path);
        byte[] bytes = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (exists) {
            jdbcTemplate.update(
                    "update knowledge_files set parent_path = ?, name = ?, node_type = ?, content = ?, "
                            + "content_type = ?, size = ?, content_hash = ?, updated_at = ? where path = ?",
                    KnowledgeFilePaths.parent(path),
                    KnowledgeFilePaths.fileName(path),
                    nodeType,
                    content,
                    contentType,
                    bytes.length,
                    sha256(bytes),
                    JdbcTimeCodec.encode(now),
                    path);
        } else {
            jdbcTemplate.update(
                    "insert into knowledge_files "
                            + "(path, parent_path, name, node_type, content, content_type, size, content_hash, created_at, updated_at) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    path,
                    KnowledgeFilePaths.parent(path),
                    KnowledgeFilePaths.fileName(path),
                    nodeType,
                    content,
                    contentType,
                    bytes.length,
                    sha256(bytes),
                    JdbcTimeCodec.encode(now),
                    JdbcTimeCodec.encode(now));
        }
    }

    private void syncIndexes(String path, String content) {
        if (KnowledgeFilePaths.isSkillManifestFile(path)) {
            upsertSkillManifest(path, content);
        }
    }

    private void upsertSkillManifest(String skillFilePath, String content) {
        String skillKey = KnowledgeFilePaths.skillKey(skillFilePath);
        String skillDirPath = KnowledgeFilePaths.skillDir(skillFilePath);
        SkillDescriptor descriptor = SkillFileParser.readDescriptor(content, skillKey, skillFilePath);
        Instant now = Instant.now();
        boolean exists = exists("skill_manifests", "skill_key", skillKey);
        if (exists) {
            jdbcTemplate.update(
                    "update skill_manifests set skill_dir_path = ?, skill_file_path = ?, name = ?, "
                            + "description = ?, updated_at = ? where skill_key = ?",
                    skillDirPath,
                    skillFilePath,
                    descriptor.getName(),
                    descriptor.getDescription(),
                    JdbcTimeCodec.encode(now),
                    skillKey);
        } else {
            jdbcTemplate.update(
                    "insert into skill_manifests "
                            + "(skill_key, skill_dir_path, skill_file_path, name, description, created_at, updated_at) "
                            + "values (?, ?, ?, ?, ?, ?, ?)",
                    skillKey,
                    skillDirPath,
                    skillFilePath,
                    descriptor.getName(),
                    descriptor.getDescription(),
                    JdbcTimeCodec.encode(now),
                    JdbcTimeCodec.encode(now));
        }
    }

    private void deleteSkillManifest(String skillFilePath) {
        if (KnowledgeFilePaths.isSkillManifestFile(skillFilePath)) {
            jdbcTemplate.update("delete from skill_manifests where skill_file_path = ?", skillFilePath);
        }
    }

    private boolean exists(String table, String column, String value) {
        Integer count = jdbcTemplate.queryForObject(
                "select count(*) from " + table + " where " + column + " = ?",
                Integer.class,
                value);
        return count != null && count.intValue() > 0;
    }

    private String sha256(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder();
            for (byte b : digest) {
                value.append(String.format("%02x", b & 0xff));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 digest is not available.", e);
        }
    }
}
