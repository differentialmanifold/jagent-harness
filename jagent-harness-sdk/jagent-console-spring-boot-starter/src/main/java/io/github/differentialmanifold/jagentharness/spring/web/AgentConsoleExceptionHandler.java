package io.github.differentialmanifold.jagentharness.spring.web;

import io.github.differentialmanifold.jagentharness.core.agent.ActiveRunException;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderException;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AgentConsoleExceptionHandler {

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> unreadableRequest(HttpMessageNotReadableException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ChatRequestBodyLimitFilter.RequestBodyTooLargeException) {
                return error(HttpStatus.PAYLOAD_TOO_LARGE, (Exception) current);
            }
            current = current.getCause();
        }
        return serverError(exception);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, exception);
    }

    @ExceptionHandler(ModelProviderException.class)
    public ResponseEntity<ErrorResponse> providerError(Exception exception) {
        return error(HttpStatus.BAD_GATEWAY, exception);
    }

    @ExceptionHandler(ActiveRunException.class)
    public ResponseEntity<ErrorResponse> activeRun(Exception exception) {
        return error(HttpStatus.CONFLICT, exception);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> serverError(Exception exception) {
        return error(HttpStatus.INTERNAL_SERVER_ERROR, exception);
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, Exception exception) {
        ErrorResponse body = new ErrorResponse(status.value(), status.getReasonPhrase(), exception.getMessage());
        return ResponseEntity.status(status).body(body);
    }
}
