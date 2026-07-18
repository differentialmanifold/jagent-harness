package io.github.differentialmanifold.jagentharness.core.agent;

public class RunInputReceipt {

    private final String inputId;
    private final RunInputStatus status;

    public RunInputReceipt(String inputId, RunInputStatus status) {
        this.inputId = inputId;
        this.status = status;
    }

    public String getInputId() {
        return inputId;
    }

    public RunInputStatus getStatus() {
        return status;
    }
}
