package io.github.differentialmanifold.jagentharness.spring.web;

public class ActiveRequestException extends RuntimeException {

    public ActiveRequestException(String requestId) {
        super("Request is already active: " + requestId);
    }
}
