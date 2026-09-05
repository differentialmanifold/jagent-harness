package io.github.differentialmanifold.jagentharness.core.conversation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import io.github.differentialmanifold.jagentharness.core.message.AgentMessage;
import io.github.differentialmanifold.jagentharness.core.message.MessageImage;
import org.junit.jupiter.api.Test;

class TokenEstimatorTest {

    @Test
    void estimatesImagesWithoutCountingBase64CharactersAsTextTokens() {
        StringBuilder largeDataUrl = new StringBuilder("data:image/png;base64,");
        for (int index = 0; index < 100_000; index++) {
            largeDataUrl.append('a');
        }
        AgentMessage message = AgentMessage.user("s1", "describe");
        message.setImages(Collections.singletonList(new MessageImage(
                "large.png", "image/png", largeDataUrl.toString())));

        int estimate = new TokenEstimator().estimateMessages(Collections.singletonList(message));

        assertTrue(estimate >= TokenEstimator.ESTIMATED_TOKENS_PER_IMAGE);
        assertTrue(estimate < 1200);
    }
}
