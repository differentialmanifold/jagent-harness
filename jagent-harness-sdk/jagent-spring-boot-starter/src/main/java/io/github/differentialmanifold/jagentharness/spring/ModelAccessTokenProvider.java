package io.github.differentialmanifold.jagentharness.spring;

@FunctionalInterface
public interface ModelAccessTokenProvider {

    /**
     * Returns the access token for the next model request. Implementations may be called concurrently.
     */
    String getAccessToken();
}
