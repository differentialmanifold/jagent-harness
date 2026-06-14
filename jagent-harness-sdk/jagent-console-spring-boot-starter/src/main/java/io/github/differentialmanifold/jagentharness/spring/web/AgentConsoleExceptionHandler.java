package io.github.differentialmanifold.jagentharness.spring.web;

import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderException;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ErrorResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class AgentConsoleExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> badRequest(Exception exception) {
        return error(HttpStatus.BAD_REQUEST, exception);
    }

    @ExceptionHandler(ModelProviderException.class)
    public ResponseEntity<ErrorResponse> providerError(Exception exception) {
        return error(HttpStatus.BAD_GATEWAY, exception);
    }

    @ExceptionHandler(ActiveRequestException.class)
    public ResponseEntity<ErrorResponse> activeRequest(Exception exception) {
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
