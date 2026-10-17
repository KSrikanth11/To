package com.example.recipient.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.dao.DataAccessException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import jakarta.validation.ConstraintViolationException;
import com.example.recipient.service.SonioxException;
import com.example.recipient.service.SonioxTimeoutException;
import com.example.recipient.service.TranscriptRejectedException;
import com.example.recipient.service.AudioFileRejectedException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.client.RestClientResponseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.sql.SQLException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<?> validation(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", "Invalid request", "message", "Check required fields and ensure query, audio, language_hints, limit, and offset satisfy their documented limits"));
    }
    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<?> malformed() { return ResponseEntity.badRequest().body(Map.of("error", "Invalid JSON request")); }
    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<?> invalidParameters() { return ResponseEntity.badRequest().body(Map.of("error", "Invalid request parameters")); }
    @ExceptionHandler(MissingServletRequestPartException.class)
    ResponseEntity<?> missingFile() { return ResponseEntity.badRequest().body(Map.of("error", "The multipart request must include an audio file")); }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> uploadTooLarge() { return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE).body(Map.of("error", "Audio file exceeds the upload limit")); }
    @ExceptionHandler(AudioFileRejectedException.class)
    ResponseEntity<?> audioRejected(AudioFileRejectedException ex) {
        return ResponseEntity.status(ex.isTooLarge() ? HttpStatus.PAYLOAD_TOO_LARGE : HttpStatus.BAD_REQUEST)
                .body(Map.of("error", ex.getMessage()));
    }
    @ExceptionHandler(DataAccessException.class)
    ResponseEntity<?> databaseUnavailable(DataAccessException ex) {
        Throwable cause = ex.getMostSpecificCause();
        if (cause instanceof SQLException sql) {
            // MySQL's message names the missing table/column or syntax issue, but does not
            // include credentials or bound search terms. Do not log the SQL statement itself.
            log.warn("recipient.database.unavailable error_type={} sql_state={} vendor_code={} db_message={}",
                    ex.getClass().getSimpleName(), sql.getSQLState(), sql.getErrorCode(), sql.getMessage());
        } else {
            log.warn("recipient.database.unavailable error_type={} cause_type={}",
                    ex.getClass().getSimpleName(), cause.getClass().getSimpleName());
        }
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("error", "Recipient database is temporarily unavailable"));
    }
    @ExceptionHandler(SonioxTimeoutException.class)
    ResponseEntity<?> transcriptionTimeout() { return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).body(Map.of("error", "Audio transcription timed out")); }
    @ExceptionHandler(SonioxException.class)
    ResponseEntity<?> transcriptionFailure(SonioxException ex) {
        Throwable cause = ex.getCause();
        if (cause instanceof RestClientResponseException response) {
            log.error("soniox.request.failed error_type={} provider_http_status={} cause_type={}",
                    ex.getClass().getSimpleName(), response.getStatusCode().value(), cause.getClass().getSimpleName());
        } else {
            log.error("soniox.request.failed error_type={} cause_type={}", ex.getClass().getSimpleName(),
                    cause == null ? "none" : cause.getClass().getSimpleName());
        }
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "Audio transcription is temporarily unavailable"));
    }
    @ExceptionHandler(TranscriptRejectedException.class)
    ResponseEntity<?> transcriptRejected(TranscriptRejectedException ex) {
        return ResponseEntity.unprocessableEntity().body(Map.of("error", ex.getMessage()));
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception ex) {
        log.error("request.error type={}", ex.getClass().getSimpleName());
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", "Request could not be completed"));
    }
}
