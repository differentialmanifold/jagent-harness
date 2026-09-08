package io.github.differentialmanifold.jagentharness.spring;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.prompt.SystemPromptContributor;
import io.github.differentialmanifold.jagentharness.core.tool.*;
import io.github.differentialmanifold.jagentharness.protocol.ToolDescriptor;
import java.util.*;
import org.springframework.context.annotation.*;

@Configuration
public class ClientToolsConfiguration {
    @Bean
    public ToolProvider clientToolContracts(ObjectMapper json) {
        return context -> {
            List<ToolDefinition> tools = new ArrayList<>();
            if (context != null)
                for (ToolDescriptor spec : context.getClientCapabilities().getTools())
                    tools.add(new ClientToolBinding(spec, json));
            return tools;
        };
    }

    @Bean
    public SystemPromptContributor applicationInstructions() {
        return context ->
                context.getAgentContext() == null
                        ? ""
                        : context.getAgentContext().getClientCapabilities().getInstructions();
    }
}
