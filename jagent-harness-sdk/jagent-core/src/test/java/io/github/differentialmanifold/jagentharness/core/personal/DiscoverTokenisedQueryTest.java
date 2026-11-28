package io.github.differentialmanifold.jagentharness.core.personal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.differentialmanifold.jagentharness.core.project.ProjectService;
import io.github.differentialmanifold.jagentharness.core.session.SessionRepository;
import io.github.differentialmanifold.jagentharness.core.session.SessionStore;
import io.github.differentialmanifold.jagentharness.protocol.RunnerSummary;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@code agents discover} must match every whitespace-separated token of the query. */
class DiscoverTokenisedQueryTest {

    private final PersonalAgentStore store = mock(PersonalAgentStore.class);
    private final ProjectService projects = mock(ProjectService.class);

    private static RunnerSummary runner(String id, String name, String platform) {
        RunnerSummary r = new RunnerSummary();
        r.runnerId = id;
        r.name = name;
        r.platform = platform;
        return r;
    }

    private static PersonalAgentStore.Profile profile(
            String id, String name, String description, String runner) {
        PersonalAgentStore.Profile p = new PersonalAgentStore.Profile();
        p.id = id;
        p.name = name;
        p.description = description;
        p.runnerId = runner;
        return p;
    }

    private PersonalAgentService service() {
        when(projects.runners(false))
                .thenReturn(
                        new ArrayList<>(
                                Arrays.asList(
                                        runner("r1", "mac-mini", "darwin"),
                                        runner("r2", "linux-box", "linux"))));
        when(store.profiles())
                .thenReturn(
                        new ArrayList<>(
                                Arrays.asList(
                                        profile(
                                                "ios-builder",
                                                "iOS Builder",
                                                "xcode builds on the mac-mini host",
                                                "r1"),
                                        profile("web-host", "Web Host", "ubuntu web server", "r2"))));
        return new PersonalAgentService(
                store,
                mock(SessionRepository.class),
                null,
                mock(SessionStore.class),
                null,
                projects,
                null,
                null,
                null);
    }

    @SuppressWarnings("unchecked")
    private static List<String> agentIds(Map<String, Object> discovery) {
        List<String> ids = new ArrayList<>();
        for (Map<String, Object> row : (List<Map<String, Object>>) discovery.get("agents"))
            ids.add(String.valueOf(row.get("agentId")));
        return ids;
    }

    @Test
    void everyTokenMustMatchPossiblyAcrossNameDescriptionAndPlatform() {
        // "xcode" is only in the description, "darwin" only in the runner platform.
        assertEquals(
                Arrays.asList("ios-builder"), agentIds(service().discoverAgents("xcode darwin")));
    }

    @Test
    void tokenOrderDoesNotMatter() {
        assertEquals(
                Arrays.asList("ios-builder"), agentIds(service().discoverAgents("Darwin XCODE")));
    }

    @Test
    void oneTokenMatchingIsEnoughForSingleTokenQueries() {
        assertEquals(Arrays.asList("ios-builder"), agentIds(service().discoverAgents("xcode")));
        assertEquals(Arrays.asList("web-host"), agentIds(service().discoverAgents("ubuntu")));
    }

    @Test
    void aTokenThatNoRowContainsExcludesThatRow() {
        // "ubuntu" matches web-host, but "xcode" does not; ios-builder has xcode but not ubuntu.
        assertEquals(Collections.emptyList(), agentIds(service().discoverAgents("ubuntu xcode")));
    }

    @Test
    void emptyQueryMatchesAll() {
        assertEquals(
                Arrays.asList("ios-builder", "web-host"), agentIds(service().discoverAgents("")));
        assertEquals(
                Arrays.asList("ios-builder", "web-host"), agentIds(service().discoverAgents("   ")));
        assertEquals(
                Arrays.asList("ios-builder", "web-host"), agentIds(service().discoverAgents(null)));
    }
}
