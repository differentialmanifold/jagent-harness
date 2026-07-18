package io.github.differentialmanifold.jagentharness.core.agent;

import java.util.Collections;
import java.util.List;

public interface RunInputSource {

    /** Claims every run input currently visible for the completed turn boundary. */
    List<RunInput> claimPendingInputs(String sessionId,
                                      String runId,
                                      String completedTurnId);

    static RunInputSource none() {
        return new RunInputSource() {
            @Override
            public List<RunInput> claimPendingInputs(String sessionId,
                                                     String runId,
                                                     String completedTurnId) {
                return Collections.emptyList();
            }
        };
    }
}
