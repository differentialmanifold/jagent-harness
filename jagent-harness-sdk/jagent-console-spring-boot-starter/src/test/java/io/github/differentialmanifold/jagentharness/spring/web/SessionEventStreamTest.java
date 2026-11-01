package io.github.differentialmanifold.jagentharness.spring.web;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import io.github.differentialmanifold.jagentharness.core.session.*;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.*;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class SessionEventStreamTest {
    @Test
    void missingSessionSendsTerminalEventInsteadOfServerError() throws Exception {
        SessionManager sessions = new DefaultSessionManager(mock(SessionRepository.class), null);
        SessionEventStream stream = new SessionEventStream(sessions, new ObjectMapper());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(stream).build();
        try {
            MvcResult result =
                    mvc.perform(get("/api/v1/sessions/deleted/events"))
                            .andExpect(request().asyncStarted())
                            .andReturn();
            String content =
                    mvc.perform(asyncDispatch(result))
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString();
            assertTrue(content.contains("event:session_missing"));
            assertTrue(content.contains("\"sessionId\":\"deleted\""));
            assertFalse(content.contains("event:snapshot"));
        } finally {
            stream.close();
        }
    }

    @Test
    void observesHistoryAndLiveEventsWithoutStartingOrStoppingWork() throws Exception {
        SessionManager sessions = mock(SessionManager.class);
        SessionRecord record = new SessionRecord();
        record.setSessionId("one");
        AgentEvent stored = AgentEvent.of("one", "r1", "t1", "message_end", "{}");
        when(sessions.getDetails("one"))
                .thenReturn(new SessionDetails(record, Collections.singletonList(stored)));
        SessionEventStream stream = new SessionEventStream(sessions, new ObjectMapper());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(stream).build();
        try {
            MvcResult first =
                    mvc.perform(get("/api/v1/sessions/one/events"))
                            .andExpect(request().asyncStarted())
                            .andReturn();
            MvcResult second =
                    mvc.perform(get("/api/v1/sessions/one/events"))
                            .andExpect(request().asyncStarted())
                            .andReturn();
            stream.onEvent(
                    AgentEvent.of(
                            "one", "r2", "t2", "message_update", "{\"delta\":\"live text\"}"));
            stream.onEvent(
                    AgentEvent.of(
                            "other",
                            "r3",
                            "t3",
                            "message_update",
                            "{\"delta\":\"other session\"}"));
            stream.close();
            for (MvcResult result : new MvcResult[] {first, second}) {
                String content =
                        mvc.perform(asyncDispatch(result))
                                .andReturn()
                                .getResponse()
                                .getContentAsString();
                assertTrue(content.contains("event:snapshot"));
                assertTrue(content.contains(stored.getEventId()));
                assertTrue(content.contains("live text"));
                assertFalse(content.contains("other session"));
            }
            verify(sessions, times(2)).getDetails("one");
            verifyNoMoreInteractions(sessions);
        } finally {
            stream.close();
        }
    }
}
