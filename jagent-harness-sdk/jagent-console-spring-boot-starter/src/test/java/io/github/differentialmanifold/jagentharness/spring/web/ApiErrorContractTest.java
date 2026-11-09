package io.github.differentialmanifold.jagentharness.spring.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import io.github.differentialmanifold.jagentharness.core.execution.*;
import io.github.differentialmanifold.jagentharness.core.fs.KnowledgeFileStore;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderException;
import io.github.differentialmanifold.jagentharness.core.session.*;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ApiErrorContractTest {
    private MockMvc mvc(ChatController chat) {
        return MockMvcBuilders.standaloneSetup(
                        chat,
                        new VirtualFileController(
                                mock(KnowledgeFileStore.class), mock(SessionManager.class)))
                .setControllerAdvice(new ApiExceptionHandler(), new ProtocolErrors())
                .build();
    }

    @Test
    void preservesFrameworkHttpStatusesAndMissingResourceSemantics() throws Exception {
        RunService service = mock(RunService.class);
        when(service.state("missing")).thenThrow(new SessionNotFoundException("missing"));
        when(service.submit(any(), any())).thenThrow(new SessionNotFoundException("missing"));
        ChatController chat = new ChatController(service);
        try {
            MockMvc mvc = mvc(chat);
            mvc.perform(get("/api/v1/chat"))
                    .andExpect(status().isMethodNotAllowed())
                    .andExpect(header().string("Allow", "POST"));
            mvc.perform(
                            post("/api/v1/chat")
                                    .accept(MediaType.TEXT_PLAIN)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{}"))
                    .andExpect(status().isNotAcceptable());
            mvc.perform(post("/api/v1/chat").contentType(MediaType.TEXT_PLAIN).content("{}"))
                    .andExpect(status().isUnsupportedMediaType());
            mvc.perform(get("/api/v1/vfs/files/content")).andExpect(status().isBadRequest());
            mvc.perform(get("/api/v1/vfs/files/content").param("path", "missing.md"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("NOT_FOUND"));
            mvc.perform(get("/api/v1/sessions/missing/state")).andExpect(status().isNotFound());
            mvc.perform(
                            post("/api/v1/chat")
                                    .accept(MediaType.APPLICATION_JSON)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"sessionId\":\"missing\"}"))
                    .andExpect(status().isNotFound());
            mvc.perform(
                            post("/api/v1/chat")
                                    .accept(MediaType.APPLICATION_JSON)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{"))
                    .andExpect(status().isBadRequest());
        } finally {
            chat.close();
        }
    }

    @Test
    void streamingAndJsonProviderErrorsShareStatusAndCode() throws Exception {
        RunService service = mock(RunService.class);
        when(service.submit(any(), any()))
                .thenAnswer(
                        invocation -> {
                            RunHandle handle = new RunHandle("s", "r", null);
                            handle.fail(new ModelProviderException("Upstream failed"));
                            return handle;
                        });
        ChatController chat = new ChatController(service);
        try {
            MockMvc mvc = mvc(chat);
            MvcResult json =
                    mvc.perform(
                                    post("/api/v1/chat")
                                            .accept(MediaType.APPLICATION_JSON)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{}"))
                            .andExpect(request().asyncStarted())
                            .andReturn();
            mvc.perform(asyncDispatch(json))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value("MODEL_PROVIDER_ERROR"));
            MvcResult stream =
                    mvc.perform(
                                    post("/api/v1/chat")
                                            .accept(MediaType.TEXT_EVENT_STREAM)
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{}"))
                            .andReturn();
            String body = stream.getResponse().getContentAsString();
            assertTrue(body.contains("event:error"), body);
            assertTrue(body.contains("\"status\":502"), body);
            assertTrue(body.contains("MODEL_PROVIDER_ERROR"), body);
            assertEquals(
                    502,
                    HttpExecution.errorBody(
                                    new CompletionException(new ModelProviderException("failure")))
                            .get("status"));
        } finally {
            chat.close();
        }
    }
}
