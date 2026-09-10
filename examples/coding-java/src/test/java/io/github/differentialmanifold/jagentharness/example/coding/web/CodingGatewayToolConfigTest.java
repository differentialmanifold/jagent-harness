package io.github.differentialmanifold.jagentharness.example.coding.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.differentialmanifold.jagentharness.example.coding.tool.CodingTool;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CodingGatewayToolConfigTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> forwardedBody = new AtomicReference<>();
    private HttpServer server;
    private CodingGateway gateway;
    private MockMvc mvc;
    private CodingTool localTool;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/tools", exchange -> {
            forwardedBody.set(new String(org.springframework.util.StreamUtils.copyToByteArray(exchange.getRequestBody()), StandardCharsets.UTF_8));
            String body = exchange.getRequestURI().getPath().endsWith("/config")
                    ? "{\"configured\":true,\"enabledTools\":[\"server_tool\"],\"tools\":[{\"name\":\"server_tool\",\"enabled\":true}]}"
                    : "[{\"name\":\"server_tool\"}]";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        localTool = mock(CodingTool.class);
        when(localTool.getName()).thenReturn("local_read");
        when(localTool.getDescription()).thenReturn("Read a local file");
        when(localTool.getParametersSchema()).thenReturn(json.readTree("{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}}}"));
        gateway = new CodingGateway(json, Collections.singletonList(localTool), mock(CodingWorkspaceService.class), "", "http://127.0.0.1:" + server.getAddress().getPort(), "");
        mvc = MockMvcBuilders.standaloneSetup(gateway).build();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (gateway != null) gateway.close();
        if (server != null) server.stop(0);
    }

    @Test
    void configIncludesClientCatalogWithoutChangingServerSelection() throws Exception {
        mvc.perform(get("/api/v1/tools/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tools[0].name").value("server_tool"))
                .andExpect(jsonPath("$.enabledTools[0]").value("server_tool"))
                .andExpect(jsonPath("$.clientTools[0].name").value("local_read"))
                .andExpect(jsonPath("$.clientTools[0].executionLocation").value("CLIENT"))
                .andExpect(jsonPath("$.clientTools[0].parametersSchema.properties.path.type").value("string"));
    }

    @Test
    void clientCatalogIsPreservedAfterSavingAndResettingServerConfiguration() throws Exception {
        mvc.perform(put("/api/v1/tools/config").contentType(MediaType.APPLICATION_JSON).content("{\"enabledTools\":[]}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.clientTools[0].name").value("local_read"));
        assertThat(json.readTree(forwardedBody.get()).get("enabledTools").size()).isZero();
        mvc.perform(delete("/api/v1/tools/config"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.clientTools[0].name").value("local_read"));
    }

    @Test
    void generalCatalogUsesTheSameSchemaFieldForClientTools() throws Exception {
        mvc.perform(get("/api/v1/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[1].parametersSchema.type").value("object"))
                .andExpect(jsonPath("$[1].parameters").doesNotExist());
    }
}
