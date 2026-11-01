package io.github.differentialmanifold.jagentharness.spring.personal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore;
import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentStore.*;
import io.github.differentialmanifold.jagentharness.core.session.*;
import java.util.*;
import org.junit.jupiter.api.Test;

class PersonalTaskPlanTest {
    private final PersonalAgentStore store = mock(PersonalAgentStore.class);
    private final PersonalTaskPlan plan =
            new PersonalTaskPlan(store, mock(SessionRepository.class), mock(SessionStore.class));

    private Card task(String id, String status) {
        Card c = new Card();
        c.id = id;
        c.title = "Title " + id;
        c.status = status;
        c.ownerId = "agent";
        c.conversationId = "chat";
        c.description = "Plan " + id;
        c.acceptance = "Criteria " + id;
        c.progress = "Progress " + id;
        return c;
    }

    @Test
    void omitsTerminalWorkExceptCompactDependencyStatusesAndKeepsConversationsIsolated() {
        Card current = task("current", "DOING");
        current.dependsOn = Arrays.asList("done", "cancelled", "foreign");
        Card done = task("done", "DONE"), cancelled = task("cancelled", "CANCELLED");
        Card old = task("unrelated-old", "DONE"), other = task("other-chat", "TODO");
        other.conversationId = "different";
        Card foreign = task("foreign", "DONE");
        foreign.ownerId = "another-agent";
        when(store.cards())
                .thenReturn(Arrays.asList(current, done, cancelled, old, other, foreign));
        when(store.inbox("WAITING")).thenReturn(Collections.emptyList());

        String context = plan.context("agent", "chat", "current");
        assertTrue(context.contains("1 unfinished tasks"));
        assertTrue(context.contains("Plan current"));
        assertTrue(context.contains("Title done"));
        assertTrue(context.contains("Title cancelled"));
        assertFalse(context.contains("Plan done"));
        assertFalse(context.contains("Criteria cancelled"));
        assertFalse(context.contains("unrelated-old"));
        assertFalse(context.contains("other-chat"));
        assertFalse(context.contains("Title foreign"));
        String otherContext = plan.context("agent", "different", null);
        assertTrue(otherContext.contains("Plan other-chat"));
        assertFalse(otherContext.contains("Plan current"));
    }

    @Test
    void focusesTheCurrentEventAndProducesStableBoundedSnapshotsRegardlessOfStoreOrder() {
        List<Card> cards = new ArrayList<>();
        for (int i = 0; i < 25; i++) cards.add(task(String.format("step-%02d", i), "TODO"));
        when(store.cards()).thenReturn(cards);
        when(store.inbox("WAITING")).thenReturn(Collections.emptyList());
        String first = plan.context("agent", "chat", "step-24");
        assertTrue(first.contains("25 unfinished tasks; showing 20"));
        assertTrue(first.contains("Plan step-24"));
        assertTrue(first.indexOf("Plan step-24") < first.indexOf("Plan step-00"));
        assertFalse(first.contains("Plan step-23"));
        Collections.reverse(cards);
        assertEquals(first, plan.context("agent", "chat", "step-24"));
    }

    @Test
    void completedEventsDoNotReinjectFinishedPlans() {
        when(store.cards()).thenReturn(Collections.singletonList(task("finished", "DONE")));
        when(store.inbox("WAITING")).thenReturn(Collections.emptyList());
        String context = plan.context("agent", "chat", "finished");
        assertTrue(context.contains("0 unfinished tasks"));
        assertFalse(context.contains("Plan finished"));
    }
}
