package lk.serenetravels.masterdataservice.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

public class InvalidRequestException extends ApiException {

    public InvalidRequestException(String message) {
        super(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, null);
    }

    public InvalidRequestException(String message, Map<String, String> fieldErrors) {
        super(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", message, fieldErrors);
    }

    public static InvalidRequestException field(String field, String problem) {
        return new InvalidRequestException("Validation failed", Map.of(field, problem));
    }
}
