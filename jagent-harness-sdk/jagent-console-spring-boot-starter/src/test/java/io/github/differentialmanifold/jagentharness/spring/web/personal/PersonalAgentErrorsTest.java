package io.github.differentialmanifold.jagentharness.spring.web.personal;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.differentialmanifold.jagentharness.core.personal.*;
import io.github.differentialmanifold.jagentharness.spring.web.ApiExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class PersonalAgentErrorsTest {
    @Test
    void mapsDomainFailuresBeforeTheGenericServerErrorHandler() throws Exception {
        PersonalAgentService agents = mock(PersonalAgentService.class);
        MockMvc mvc =
                MockMvcBuilders.standaloneSetup(
                                new PersonalAgentController(
                                        agents,
                                        mock(PersonalAgentStore.class),
                                        mock(PersonalAgentKnowledge.class),
                                        mock(PersonalAgentLearning.class)))
                        .setControllerAdvice(new PersonalAgentErrors(), new ApiExceptionHandler())
                        .build();
        doThrow(
                        new PersonalAgentException(
                                PersonalAgentException.Kind.NOT_FOUND,
                                "AGENT_NOT_FOUND",
                                "Agent not found"))
                .when(agents)
                .require("missing");
        mvc.perform(get("/api/v1/personal/agents/missing/conversations"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_NOT_FOUND"));
        doThrow(
                        new PersonalAgentException(
                                PersonalAgentException.Kind.CONFLICT,
                                "AGENT_BUSY",
                                "Agent is running"))
                .when(agents)
                .deleteConversation("agent", "chat");
        mvc.perform(post("/api/v1/personal/agents/agent/conversations/chat/delete"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("AGENT_BUSY"));
    }
}
