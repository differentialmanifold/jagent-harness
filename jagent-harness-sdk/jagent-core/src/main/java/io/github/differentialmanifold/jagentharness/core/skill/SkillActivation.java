package io.github.differentialmanifold.jagentharness.core.skill;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** An immutable, append-only activation receipt; contains no instructions or credentials. */
public final class SkillActivation {
    private final String path;
    private final String source;
    private final String version;
    private final List<SkillToolBinding> tools;

    @JsonCreator
    public SkillActivation(
            @JsonProperty("path") String path,
            @JsonProperty("source") String source,
            @JsonProperty("version") String version,
            @JsonProperty("tools") List<SkillToolBinding> tools) {
        this.path = path;
        this.source = source;
        this.version = version;
        this.tools = Collections.unmodifiableList(new ArrayList<>(tools));
    }

    public String getPath() {
        return path;
    }

    public String getSource() {
        return source;
    }

    public String getVersion() {
        return version;
    }

    public List<SkillToolBinding> getTools() {
        return tools;
    }

    public String activationId() {
        return digest(source + "\n" + path + "\n" + version);
    }

    public static String digest(String value) {
        try {
            byte[] hash =
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder();
            for (byte b : hash) result.append(String.format("%02x", b & 0xff));
            return result.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
