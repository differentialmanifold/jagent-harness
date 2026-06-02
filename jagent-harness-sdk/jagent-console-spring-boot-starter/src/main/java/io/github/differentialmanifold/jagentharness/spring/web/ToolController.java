package io.github.differentialmanifold.jagentharness.spring.web;

import java.util.ArrayList;
import java.util.List;

import io.github.differentialmanifold.jagentharness.core.tool.ToolDefinition;
import io.github.differentialmanifold.jagentharness.core.tool.ToolRegistry;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ToolInfoResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolRegistry toolRegistry;

    public ToolController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @GetMapping
    public List<ToolInfoResponse> list() {
        List<ToolInfoResponse> tools = new ArrayList<ToolInfoResponse>();
        for (ToolDefinition tool : toolRegistry.all()) {
            tools.add(new ToolInfoResponse(tool.getName(), tool.getDescription(), tool.getParametersSchema()));
        }
        return tools;
    }
}
