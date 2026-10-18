package lk.serenetravels.masterdataservice.exception;

import org.springframework.http.HttpStatus;

import java.util.Map;

// Base for every error the API reports deliberately. The stable `code` is what the FE switches on;
// `fieldErrors` (field -> message) is what it binds onto form inputs.
public abstract class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, String> fieldErrors;

    protected ApiException(HttpStatus status, String code, String message, Map<String, String> fieldErrors) {
        super(message);
        this.status = status;
        this.code = code;
        this.fieldErrors = fieldErrors == null ? Map.of() : Map.copyOf(fieldErrors);
    }

    public HttpStatus getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    public Map<String, String> getFieldErrors() {
        return fieldErrors;
    }
}
