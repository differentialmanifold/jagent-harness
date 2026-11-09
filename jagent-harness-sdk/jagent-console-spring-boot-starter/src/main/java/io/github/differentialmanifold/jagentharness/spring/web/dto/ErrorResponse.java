package io.github.differentialmanifold.jagentharness.spring.web.dto;

public class ErrorResponse {

    private int status;
    private String error;
    private String message;

    public ErrorResponse() {}

    public ErrorResponse(int status, String error, String message) {
        this.status = status;
        this.error = error;
        this.message = message;
    }

    public String getCode() {
        return codeFor(status);
    }

    public static String codeFor(int status) {
        switch (status) {
            case 400:
                return "BAD_REQUEST";
            case 404:
                return "NOT_FOUND";
            case 409:
                return "CONFLICT";
            case 502:
                return "MODEL_PROVIDER_ERROR";
            case 500:
                return "INTERNAL_ERROR";
            default:
                return "HTTP_" + status;
        }
    }

    public int getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }
}
