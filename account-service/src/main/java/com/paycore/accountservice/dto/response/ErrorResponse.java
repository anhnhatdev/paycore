package com.paycore.accountservice.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Standard error response format.
 * traceId enables correlation with distributed tracing (Zipkin/Jaeger).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {
    private int statusCode;
    private String error;
    private String message;
    private String traceId;
    private LocalDateTime timestamp;
}
