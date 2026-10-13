package io.github.differentialmanifold.jagentharness.spring.web;

import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(
        assignableTypes = {ChatController.class, RunnerController.class, SessionController.class})
@org.springframework.core.annotation.Order(-100)
public class ProtocolErrors {
    @ExceptionHandler(ProtocolException.class)
    public ResponseEntity<Map<String, Object>> handle(ProtocolException error) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", error.code);
        body.put("message", error.getMessage());
        return ResponseEntity.status(error.status)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .body(body);
    }
}
