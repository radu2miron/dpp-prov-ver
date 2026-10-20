package edu.tucn.dppprovver.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;

/**
 * @author <a href="mailto:radu.miro@aut.utcluj.ro">Radu Miron</a>
 */
@RestControllerAdvice
public class RestResponseExceptionHandler {
    public record ErrorResponse(String error, String message, String stage,
                                String transactionId, String validationCode, Instant timestamp) {
    }

    @ExceptionHandler(BlockchainRelatedException.class)
    public ResponseEntity<ErrorResponse> blockchain(BlockchainRelatedException e) {
        HttpStatus status = "COMMIT".equals(e.getStage()) ? HttpStatus.CONFLICT
                : "ENDORSE".equals(e.getStage()) ? HttpStatus.UNPROCESSABLE_ENTITY
                  : HttpStatus.BAD_GATEWAY;
        return ResponseEntity.status(status).body(new ErrorResponse("FABRIC_" + e.getStage(),
                e.getMessage(), e.getStage(), e.getTransactionId(), e.getValidationCode(), Instant.now()));
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> notFound(NotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ErrorResponse(
                "NOT_FOUND", e.getMessage(), null, null, null, Instant.now()));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> unauthorized(UnauthorizedException e) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(new ErrorResponse(
                "UNAUTHORIZED", e.getMessage(), null, null, null, Instant.now()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> invalidInput(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(new ErrorResponse(
                "INVALID_INPUT", e.getMessage(), null, null, null, Instant.now()));
    }
}
