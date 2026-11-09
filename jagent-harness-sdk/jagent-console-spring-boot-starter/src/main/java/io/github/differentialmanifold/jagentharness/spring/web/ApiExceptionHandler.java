package io.github.differentialmanifold.jagentharness.spring.web;

import io.github.differentialmanifold.jagentharness.core.agent.ActiveRunException;
import io.github.differentialmanifold.jagentharness.core.provider.ModelProviderException;
import io.github.differentialmanifold.jagentharness.core.session.SessionNotFoundException;
import io.github.differentialmanifold.jagentharness.spring.web.dto.ErrorResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException exception,
            HttpHeaders headers,
            HttpStatus status,
            WebRequest request) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ChatRequestBodyLimitFilter.RequestBodyTooLargeException) {
                return handleExceptionInternal(
                        exception, null, headers, HttpStatus.PAYLOAD_TOO_LARGE, request);
            }
            current = current.getCause();
        }
        return handleExceptionInternal(exception, null, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception exception,
            Object body,
            HttpHeaders headers,
            HttpStatus status,
            WebRequest request) {
        return ResponseEntity.status(status)
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(
                        new ErrorResponse(
                                status.value(), status.getReasonPhrase(), exception.getMessage()));
    }

    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(Exception exception) {
        return error(HttpStatus.NOT_FOUND, exception);
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
        ErrorResponse body =
                new ErrorResponse(status.value(), status.getReasonPhrase(), exception.getMessage());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON).body(body);
    }
}
