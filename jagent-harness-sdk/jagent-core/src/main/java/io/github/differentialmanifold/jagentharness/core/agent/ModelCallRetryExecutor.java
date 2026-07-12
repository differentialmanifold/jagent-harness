package io.github.differentialmanifold.jagentharness.core.agent;

import java.util.LinkedHashMap;
import java.util.Map;

import io.github.differentialmanifold.jagentharness.core.event.AgentEvent;
import io.github.differentialmanifold.jagentharness.core.event.AgentEventPublisher;
import io.github.differentialmanifold.jagentharness.core.provider.ModelDeltaConsumer;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProvider;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderException;
import io.github.differentialmanifold.jagentharness.core.provider.ModelRequest;
import io.github.differentialmanifold.jagentharness.core.provider.ModelResponse;

public class ModelCallRetryExecutor {

    private final AgentSettings settings;
    private final AgentEventPublisher eventPublisher;

    public ModelCallRetryExecutor(AgentSettings settings, AgentEventPublisher eventPublisher) {
        this.settings = settings;
        this.eventPublisher = eventPublisher;
    }

    public ModelResponse call(ModelProvider provider,
                              ModelRequest request,
                              ModelDeltaConsumer deltaConsumer,
                              Runnable resetAttempt,
                              StopSignal stopSignal,
                              String sessionId,
                              String turnId) {
        StopSignal effectiveStopSignal = stopSignal == null ? StopSignal.none() : stopSignal;
        Runnable effectiveResetAttempt = resetAttempt == null ? new Runnable() {
            @Override
            public void run() {
            }
        } : resetAttempt;
        int maxAttempts = effectiveMaxAttempts();
        long delayMillis = effectiveInitialDelayMillis();
        int attempt = 1;
        while (true) {
            effectiveStopSignal.throwIfAborted();
            try {
                return provider.chat(request, deltaConsumer, effectiveStopSignal);
            } catch (ModelProviderException e) {
                if (!shouldRetry(e, attempt, maxAttempts)) {
                    throw e;
                }
                effectiveResetAttempt.run();
                long retryDelayMillis = delayMillis;
                publishRetry(
                        sessionId,
                        turnId,
                        provider,
                        attempt,
                        attempt + 1,
                        maxAttempts,
                        retryDelayMillis,
                        e);
                sleepBeforeRetry(retryDelayMillis, effectiveStopSignal);
                delayMillis = nextDelayMillis(delayMillis);
                attempt++;
            }
        }
    }

    private boolean shouldRetry(ModelProviderException exception, int attempt, int maxAttempts) {
        return settings.isModelRetryEnabled()
                && exception != null
                && exception.isRetryable()
                && attempt < maxAttempts;
    }

    private int effectiveMaxAttempts() {
        if (!settings.isModelRetryEnabled()) {
            return 1;
        }
        return Math.max(1, settings.getModelRetryMaxAttempts());
    }

    private long effectiveInitialDelayMillis() {
        return Math.max(0L, settings.getModelRetryInitialDelayMillis());
    }

    private long effectiveMaxDelayMillis() {
        return Math.max(effectiveInitialDelayMillis(), settings.getModelRetryMaxDelayMillis());
    }

    private long nextDelayMillis(long currentDelayMillis) {
        long maxDelayMillis = effectiveMaxDelayMillis();
        if (currentDelayMillis <= 0L) {
            return Math.min(100L, maxDelayMillis);
        }
        long nextDelayMillis = currentDelayMillis * 2L;
        if (nextDelayMillis < 0L) {
            return maxDelayMillis;
        }
        return Math.min(nextDelayMillis, maxDelayMillis);
    }

    private void sleepBeforeRetry(long delayMillis, StopSignal stopSignal) {
        long remainingMillis = Math.max(0L, delayMillis);
        long deadline = System.currentTimeMillis() + remainingMillis;
        while (remainingMillis > 0L) {
            stopSignal.throwIfAborted();
            try {
                Thread.sleep(Math.min(remainingMillis, 100L));
            } catch (InterruptedException e) {
                if (stopSignal.isAborted()) {
                    Thread.interrupted();
                    throw new StopRequestedException(e);
                }
                Thread.currentThread().interrupt();
                throw new ModelProviderException("Model retry wait was interrupted", e, false);
            }
            remainingMillis = deadline - System.currentTimeMillis();
        }
        stopSignal.throwIfAborted();
    }

    private void publishRetry(String sessionId,
                              String turnId,
                              ModelProvider provider,
                              int failedAttempt,
                              int nextAttempt,
                              int maxAttempts,
                              long delayMillis,
                              ModelProviderException exception) {
        if (eventPublisher == null) {
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("attempt", failedAttempt);
        payload.put("nextAttempt", nextAttempt);
        payload.put("maxAttempts", maxAttempts);
        payload.put("delayMillis", delayMillis);
        payload.put("provider", provider == null ? "" : provider.getName());
        payload.put("model", settings.getModel());
        payload.put("resetOutput", true);
        payload.put("message", "Model request failed, retrying attempt " + nextAttempt + " of " + maxAttempts);
        payload.put("error", exception == null ? "" : exception.getMessage());
        eventPublisher.publish(sessionId, turnId, AgentEvent.MODEL_RETRY, payload);
    }
}
