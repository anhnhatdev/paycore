package com.paycore.accountservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Standard error response format.
 * traceId enables correlation with distributed tracing (Zipkin/Jaeger).
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    private int statusCode;
    private String error;
    private String message;
    private String traceId;
    private LocalDateTime timestamp;
}
