package io.github.differentialmanifold.jagentharness.tools.coding;

import com.fasterxml.jackson.databind.*;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import io.github.differentialmanifold.jagentharness.tools.coding.tool.*;
import io.github.differentialmanifold.jagentharness.tools.coding.tool.support.*;
import java.util.*;

public final class CodingTools {

    private final RipgrepSearchEngine search =
            new RipgrepSearchEngine(
                    new RipgrepProcessRunner(),
                    new RipgrepBinaryResolver(System.getenv().getOrDefault("JAGENT_RG_PATH", ""))
                            .resolve());

    public Map<String, String> toolDiagnostics() {
        String backend =
                search.getExecutable()
                        .map(rg -> rg.getVersion() + " · " + rg.getPath())
                        .orElse("Java fallback (ripgrep unavailable on PATH)");
        Map<String, String> diagnostics = new LinkedHashMap<>();
        diagnostics.put("find", backend);
        diagnostics.put("grep", backend);
        return diagnostics;
    }

    public String instructions() {
        return "### Coding Tool Usage\n"
                + "- Prefer dedicated tools over bash when a tool directly covers the operation.\n"
                + "- Use find with a glob pattern to locate files; use ls to inspect a directory.\n"
                + "- Use grep with a regular expression to search file contents; set literal when searching exact text.\n"
                + "- Use read to inspect file contents; use offset and limit to read only the relevant line range.\n"
                + "- Before edit, read the current file. Each oldText must come from the current file content and include enough surrounding context to match exactly one location.\n"
                + "- Use edit for localized changes to existing files. When changing multiple locations in the same file, include all replacements in one edit call's edits array; every oldText is matched against the same original snapshot.\n"
                + "- Delete text with an empty newText. To insert text, keep a unique anchor in oldText and include that anchor plus the insertion in newText.\n"
                + "- If edit reports a conflict or that text was not found, read the current file again and retry using its latest content.\n"
                + "- Use write only when creating a new file or intentionally replacing a whole file.\n"
                + "- When enabled, bash is available for general shell commands; prefer dedicated tools first when they directly cover the operation.";
    }

    public Collection<? extends ToolDefinition> tools() {
        ObjectMapper json = new ObjectMapper();
        WorkspacePathResolver paths = new WorkspacePathResolver();
        List<ToolDefinition> coding =
                Arrays.asList(
                        new ReadTool(json, paths),
                        new LsTool(json, paths),
                        new FindTool(json, paths, search),
                        new GrepTool(json, paths, search),
                        new WriteTool(json, paths),
                        new EditTool(json, paths),
                        new BashTool(json, paths));
        return coding;
    }
}
