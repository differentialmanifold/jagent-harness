package io.github.differentialmanifold.jagentharness.example.coding.config;

import com.fasterxml.jackson.databind.ObjectMapper;
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
