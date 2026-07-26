package io.github.differentialmanifold.jagentharness.example.coding.config;

import java.util.Optional;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.prompt.SystemPromptContributor;
import io.github.differentialmanifold.jagentharness.example.coding.tool.BashTool;
import io.github.differentialmanifold.jagentharness.example.coding.tool.EditTool;
import io.github.differentialmanifold.jagentharness.example.coding.tool.FindTool;
import io.github.differentialmanifold.jagentharness.example.coding.tool.GrepTool;
import io.github.differentialmanifold.jagentharness.example.coding.tool.LsTool;
import io.github.differentialmanifold.jagentharness.example.coding.tool.ReadTool;
import io.github.differentialmanifold.jagentharness.example.coding.tool.WriteTool;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.RipgrepBinaryResolver;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.RipgrepExecutable;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.RipgrepProcessRunner;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.RipgrepSearchEngine;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.WorkspacePathResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CodingToolConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(CodingToolConfiguration.class);

    @Bean
    public WorkspacePathResolver workspacePathResolver() {
        return new WorkspacePathResolver();
    }

    @Bean
    public SystemPromptContributor codingToolSystemPromptContributor() {
        return context -> "### Coding Tool Usage\n"
                + "- Prefer dedicated tools over bash when a tool directly covers the operation.\n"
                + "- Use find with a glob pattern to locate files; use ls to inspect a directory.\n"
                + "- Use grep with a regular expression to search file contents; set literal when searching exact text.\n"
                + "- Use read to inspect file contents; use offset and limit to read only the relevant line range.\n"
                + "- Before edit, read the current file. Each oldText must come from the current file content and include enough surrounding context to match exactly one location.\n"
                + "- Use edit for localized changes to existing files. When changing multiple locations in the same file, include all replacements in one edit call's edits array; every oldText is matched against the same original snapshot.\n"
                + "- Delete text with an empty newText. To insert text, keep a unique anchor in oldText and include that anchor plus the insertion in newText.\n"
                + "- If edit reports a conflict or that text was not found, read the current file again and retry using its latest content.\n"
                + "- Use write only when creating a new file or intentionally replacing a whole file.\n"
                + "- Bash remains available for general shell commands; prefer dedicated tools first when they directly cover the operation.";
    }

    @Bean
    public BashTool bashTool(ObjectMapper objectMapper, WorkspacePathResolver pathResolver) {
        return new BashTool(objectMapper, pathResolver);
    }

    @Bean
    public EditTool editTool(ObjectMapper objectMapper, WorkspacePathResolver pathResolver) {
        return new EditTool(objectMapper, pathResolver);
    }

    @Bean
    public WriteTool writeTool(ObjectMapper objectMapper, WorkspacePathResolver pathResolver) {
        return new WriteTool(objectMapper, pathResolver);
    }

    @Bean
    public ReadTool readTool(ObjectMapper objectMapper, WorkspacePathResolver pathResolver) {
        return new ReadTool(objectMapper, pathResolver);
    }

    @Bean
    public RipgrepBinaryResolver ripgrepBinaryResolver(
            @Value("${harness.coding-tools.search.rg-path:}") String configuredPath) {
        return new RipgrepBinaryResolver(configuredPath);
    }

    @Bean
    public RipgrepProcessRunner ripgrepProcessRunner() {
        return new RipgrepProcessRunner();
    }

    @Bean
    public RipgrepSearchEngine ripgrepSearchEngine(RipgrepBinaryResolver binaryResolver,
                                                   RipgrepProcessRunner processRunner) {
        Optional<RipgrepExecutable> executable = binaryResolver.resolve();
        if (executable.isPresent()) {
            LOGGER.info(
                    "Search backend selected: ripgrep {} ({})",
                    executable.get().getVersion(),
                    executable.get().getPath());
        } else {
            LOGGER.info("Search backend selected: Java (ripgrep was not found in the current process PATH)");
        }
        return new RipgrepSearchEngine(processRunner, executable);
    }

    @Bean
    public GrepTool grepTool(ObjectMapper objectMapper,
                             WorkspacePathResolver pathResolver,
                             RipgrepSearchEngine ripgrepSearchEngine) {
        return new GrepTool(objectMapper, pathResolver, ripgrepSearchEngine);
    }

    @Bean
    public FindTool findTool(ObjectMapper objectMapper,
                             WorkspacePathResolver pathResolver,
                             RipgrepSearchEngine ripgrepSearchEngine) {
        return new FindTool(objectMapper, pathResolver, ripgrepSearchEngine);
    }

    @Bean
    public LsTool lsTool(ObjectMapper objectMapper, WorkspacePathResolver pathResolver) {
        return new LsTool(objectMapper, pathResolver);
    }
}
