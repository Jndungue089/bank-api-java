package com.sb24.api.web;

import com.sb24.api.security.RequestIdFilter;
import com.sb24.api.service.ApiException;
import java.net.URI;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** One error shape (RFC 7807). Internals (stack traces, SQL, class names) never reach the client. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final com.sb24.api.service.AuditService audit;

    public GlobalExceptionHandler(com.sb24.api.service.AuditService audit) { this.audit = audit; }

    @ExceptionHandler(com.sb24.api.access.PolicyDeniedException.class)
    ResponseEntity<Object> policyDenied(com.sb24.api.access.PolicyDeniedException e) {
        try {
            audit.denied(e.subject(), e.permission(), e.policy(), e.reason(), e.resource());
        } catch (RuntimeException ex) {
            log.error("could not audit access denial", ex);
        }
        return problem(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<Object> api(ApiException e) {
        return problem(e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler({DataIntegrityViolationException.class, ObjectOptimisticLockingFailureException.class, PessimisticLockingFailureException.class})
    ResponseEntity<Object> conflict(Exception e) {
        log.warn("conflict: {}", e.getClass().getSimpleName());
        return problem(HttpStatus.CONFLICT, "CONFLICT", "Conflito de concorrência. Repita o pedido.");
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<Object> denied(AccessDeniedException e) {
        return problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "Sem permissão para este recurso.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception e) {
        log.error("unhandled error requestId={}", MDC.get(RequestIdFilter.MDC_KEY), e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Erro interno. Indique o requestId ao suporte.");
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex, HttpHeaders headers,
                                                                  HttpStatusCode status, WebRequest request) {
        List<String> fields = ex.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getField).distinct().sorted().toList(); // field names only: never echo rejected values (passwords!)
        ProblemDetail pd = base(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Dados inválidos.");
        pd.setProperty("fields", fields);
        return ResponseEntity.badRequest().contentType(MediaType.APPLICATION_PROBLEM_JSON).body(pd);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(org.springframework.http.converter.HttpMessageNotReadableException ex,
                                                                  HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return problem(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Corpo do pedido inválido.");
    }

    private static ResponseEntity<Object> problem(HttpStatus status, String code, String detail) {
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_PROBLEM_JSON).body(base(status, code, detail));
    }

    private static ProblemDetail base(HttpStatus status, String code, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(URI.create("about:blank"));
        pd.setTitle(status.getReasonPhrase());
        pd.setProperty("code", code);
        pd.setProperty("requestId", MDC.get(RequestIdFilter.MDC_KEY));
        return pd;
    }
}
