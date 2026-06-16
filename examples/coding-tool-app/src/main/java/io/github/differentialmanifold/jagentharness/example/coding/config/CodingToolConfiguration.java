package io.github.differentialmanifold.jagentharness.example.coding.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.prompt.SystemPromptContributor;
import io.github.differentialmanifold.jagentharness.example.coding.tool.BashTool;
import io.github.differentialmanifold.jagentharness.example.coding.tool.EditTool;
import io.github.differentialmanifold.jagentharness.example.coding.tool.FindTool;
import io.github.differentialmanifold.jagentharness.example.coding.tool.GrepTool;
import io.github.differentialmanifold.jagentharness.example.coding.tool.LsTool;
import io.github.differentialmanifold.jagentharness.example.coding.tool.ReadTool;
import io.github.differentialmanifold.jagentharness.example.coding.tool.WriteTool;
import io.github.differentialmanifold.jagentharness.example.coding.tool.support.WorkspacePathResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CodingToolConfiguration {

    @Bean
    public WorkspacePathResolver workspacePathResolver() {
        return new WorkspacePathResolver();
    }

    @Bean
    public SystemPromptContributor codingToolSystemPromptContributor() {
        return context -> "### Coding Tool Usage\n"
                + "- Prefer dedicated tools over bash when a tool directly covers the operation.\n"
                + "- Use find to locate files or directories by name, glob, type, depth, or exclusions.\n"
                + "- Use grep to search text file contents.\n"
                + "- Use read to inspect file contents; use offset and limit to read only the relevant line range.\n"
                + "- Use edit for localized changes to existing files, including exact replacements, line-range replacements, deletions, and insertions.\n"
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
    public GrepTool grepTool(ObjectMapper objectMapper, WorkspacePathResolver pathResolver) {
        return new GrepTool(objectMapper, pathResolver);
    }

    @Bean
    public FindTool findTool(ObjectMapper objectMapper, WorkspacePathResolver pathResolver) {
        return new FindTool(objectMapper, pathResolver);
    }

    @Bean
    public LsTool lsTool(ObjectMapper objectMapper, WorkspacePathResolver pathResolver) {
        return new LsTool(objectMapper, pathResolver);
    }
}
