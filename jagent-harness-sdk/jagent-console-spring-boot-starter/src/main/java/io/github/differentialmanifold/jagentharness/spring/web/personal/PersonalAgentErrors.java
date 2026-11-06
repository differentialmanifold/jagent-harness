package io.github.differentialmanifold.jagentharness.spring.web.personal;

import io.github.differentialmanifold.jagentharness.core.personal.PersonalAgentException;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Domain failures keep their meaning across HTTP entry points, including session endpoints. */
@RestControllerAdvice
@org.springframework.core.annotation.Order(-110)
public class PersonalAgentErrors {
    @ExceptionHandler(PersonalAgentException.class)
    public ResponseEntity<Map<String, Object>> handle(PersonalAgentException error) {
        int status;
        switch (error.kind) {
            case NOT_FOUND:
                status = 404;
                break;
            case CONFLICT:
                status = 409;
                break;
            case FORBIDDEN:
                status = 403;
                break;
            case INVALID:
                status = 400;
                break;
            case UNAVAILABLE:
                status = 503;
                break;
            default:
                status = 500;
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", error.code);
        body.put("message", error.getMessage());
        return ResponseEntity.status(status).body(body);
    }
}
