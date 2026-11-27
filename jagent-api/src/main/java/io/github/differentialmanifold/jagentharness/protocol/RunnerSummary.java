package io.github.differentialmanifold.jagentharness.protocol;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.*;

/** Directory entry shared by Console and Agent discovery. */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RunnerSummary {
    public String runnerId, name, version;
    public String runtime = "", description = "", platform = "";
    public List<String> capabilities = new ArrayList<>();
    public boolean workspaces, online, hidden;
    public String workspaceRoot;
    public long lastSeen;
}
