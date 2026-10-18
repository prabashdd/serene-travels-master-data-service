package lk.serenetravels.masterdataservice.controller;

import lk.serenetravels.masterdataservice.exception.ApiException;
import lk.serenetravels.masterdataservice.util.SqlErrors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.LinkedHashMap;
import java.util.Map;

// Every error leaves as RFC 7807 application/problem+json with a stable `code` and a
// `fieldErrors` map, so the FE has exactly one error shape to handle.
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException ex) {
        return problem(ex.getStatus(), ex.getCode(), ex.getMessage(), ex.getFieldErrors());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleBeanValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> fieldErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Validation failed", fieldErrors);
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    public ProblemDetail handleMalformed(Exception ex) {
        return problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Request body or parameter is malformed", Map.of());
    }

    // Constraint failures the services did not pre-check. Mapped by SQLSTATE rather than by message
    // text so the response does not depend on the database's wording.
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ProblemDetail handleIntegrity(DataIntegrityViolationException ex) {
        String sqlState = SqlErrors.sqlState(ex);
        return switch (sqlState == null ? "" : sqlState) {
            case "23505" -> problem(HttpStatus.CONFLICT, "DUPLICATE",
                    "A record with the same unique value already exists", Map.of());
            case "23503" -> problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "A referenced record does not exist", Map.of());
            case "23502", "23514" -> problem(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "A value violates a database rule", Map.of());
            default -> {
                log.error("Unmapped data integrity violation", ex);
                yield problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Unexpected data error", Map.of());
            }
        };
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail, Map<String, String> fieldErrors) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(status.getReasonPhrase());
        problem.setProperty("code", code);
        if (!fieldErrors.isEmpty()) {
            problem.setProperty("fieldErrors", fieldErrors);
        }
        return problem;
    }
}
