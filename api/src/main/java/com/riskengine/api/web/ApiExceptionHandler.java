package com.riskengine.api.web;

import com.riskengine.api.riskscore.RiskScoreNotFoundException;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(RiskScoreNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(RiskScoreNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
            .body(Map.of("error", "not_found", "message", ex.getMessage()));
    }
}
