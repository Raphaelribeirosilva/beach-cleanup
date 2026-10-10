package com.raphael.beachcleanup.exceptions;

import java.time.Instant;
import org.springframework.dao.DataIntegrityViolationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@ControllerAdvice
public class ResourceExceptionHandler extends ResponseEntityExceptionHandler {

    @ExceptionHandler(EventStateConflictException.class)
    public ResponseEntity<StandardError> eventStateConflict(EventStateConflictException e,
            HttpServletRequest request) {
        return ResponseEntity.status(409).body(new StandardError(Instant.now(), 409, "Event state conflict",
            e.getMessage(), request.getRequestURI()));
    }

    @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
    public ResponseEntity<StandardError> concurrentUpdate(
            org.springframework.dao.OptimisticLockingFailureException e, HttpServletRequest request) {
        return ResponseEntity.status(409).body(new StandardError(Instant.now(), 409, "Concurrent update",
            "O registro foi alterado por outra operação. Consulte os dados atuais e tente novamente.",
            request.getRequestURI()));
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<StandardError> authentication(
            org.springframework.security.core.AuthenticationException e, HttpServletRequest request) {
        return ResponseEntity.status(401).header("WWW-Authenticate", "Bearer")
            .body(new StandardError(Instant.now(), 401, "Unauthorized",
                "Autenticação necessária ou credenciais inválidas.", request.getRequestURI()));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<StandardError> accessDenied(
            org.springframework.security.access.AccessDeniedException e, HttpServletRequest request) {
        return ResponseEntity.status(403).body(new StandardError(Instant.now(), 403, "Forbidden",
            "Você não tem permissão para acessar este recurso.", request.getRequestURI()));
    }

    @ExceptionHandler(EmailConflictException.class)
    public ResponseEntity<StandardError> emailConflict(EmailConflictException e, HttpServletRequest request) {
        return ResponseEntity.status(409).body(new StandardError(Instant.now(), 409, "Email conflict",
            e.getMessage(), request.getRequestURI()));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ValidationError err = validationError(((ServletWebRequest) request).getRequest());
        e.getBindingResult().getFieldErrors().stream()
            .sorted(java.util.Comparator.comparing(org.springframework.validation.FieldError::getField)
                .thenComparing(org.springframework.validation.FieldError::getDefaultMessage))
            .forEach(field -> err.addError(field.getField(), field.getDefaultMessage()));
        return handleExceptionInternal(e, err, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e, Object body,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (!(body instanceof StandardError)) {
            String message = switch (status.value()) {
                case 400 -> "Requisição inválida. Verifique o JSON, os tipos e os parâmetros enviados.";
                case 404 -> "O recurso solicitado não foi encontrado.";
                case 405 -> "Método HTTP não permitido para este recurso.";
                case 406 -> "O formato de resposta solicitado não é suportado.";
                case 415 -> "Tipo de conteúdo não suportado. Envie application/json.";
                default -> status.is5xxServerError() ? "Não foi possível processar a requisição."
                    : "A requisição não pôde ser processada.";
            };
            body = new StandardError(Instant.now(), status.value(), reason(status), message,
                ((ServletWebRequest) request).getRequest().getRequestURI());
        }
        return super.handleExceptionInternal(e, body, headers, status, request);
    }

    @ExceptionHandler(FieldValidationException.class)
    public ResponseEntity<ValidationError> fieldValidation(FieldValidationException e,
            HttpServletRequest request) {
        ValidationError err = validationError(request);
        err.addError(e.getFieldName(), e.getMessage());
        return ResponseEntity.badRequest().body(err);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<StandardError> unexpected(Exception e, HttpServletRequest request) {
        logger.error("Falha inesperada ao processar a requisição", e);
        return ResponseEntity.internalServerError().body(new StandardError(Instant.now(), 500,
            "Internal Server Error", "Não foi possível processar a requisição.", request.getRequestURI()));
    }

    private ValidationError validationError(HttpServletRequest request) {
        ValidationError err = new ValidationError();
        err.setTimestamp(Instant.now());
        err.setStatus(400);
        err.setError("Validation error");
        err.setMessage("Verifique os campos informados.");
        err.setPath(request.getRequestURI());
        return err;
    }

    private String reason(HttpStatusCode status) {
        HttpStatus httpStatus = HttpStatus.resolve(status.value());
        return httpStatus == null ? "HTTP error" : httpStatus.getReasonPhrase();
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<StandardError> resourceNotFound(ResourceNotFoundException e, HttpServletRequest request) {
        String error = "Resource not found";
        HttpStatus status = HttpStatus.NOT_FOUND; // Erro 404
        StandardError err = new StandardError(Instant.now(), status.value(), error, e.getMessage(), request.getRequestURI());
        
        return ResponseEntity.status(status).body(err);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<StandardError> integrityViolation(DataIntegrityViolationException e,
            HttpServletRequest request) {
        HttpStatus status = HttpStatus.CONFLICT;
        StandardError err = new StandardError(Instant.now(), status.value(), "Integrity conflict",
            "A operação conflita com registros vinculados ou restrições do banco de dados.",
            request.getRequestURI());
        return ResponseEntity.status(status).body(err);
    }
}
