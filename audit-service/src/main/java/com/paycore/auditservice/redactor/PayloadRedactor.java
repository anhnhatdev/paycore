package com.paycore.auditservice.redactor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Component
@RequiredArgsConstructor
public class PayloadRedactor {

    private final ObjectMapper objectMapper;

    private static final Set<String> SENSITIVE_FIELDS = Set.of(
            "cardnumber",
            "card_number",
            "pan",
            "cvv",
            "cvv2",
            "cvc",
            "password",
            "passwordhash",
            "password_hash",
            "pin",
            "otp",
            "otpcode",
            "otp_code",
            "secretkey",
            "secret_key",
            "privatekey",
            "private_key",
            "apikey",
            "api_key"
    );

    public static final String REDACTED_PLACEHOLDER = "[REDACTED]";

    /**
     * Recursively traverses JSON payload and sanitizes sensitive fields.
     * @return sanitized JSON string
     */
    public String redactPayload(String rawPayloadJson, String sourceService, String eventType) {
        if (rawPayloadJson == null || rawPayloadJson.isBlank()) {
            return "{}";
        }

        try {
            JsonNode root = objectMapper.readTree(rawPayloadJson);
            AtomicBoolean redactionTriggered = new AtomicBoolean(false);

            JsonNode sanitizedRoot = traverseAndRedact(root, redactionTriggered);

            if (redactionTriggered.get()) {
                log.warn("🚨 [SECURITY-WARNING] Sensitive data was detected and redacted in audit payload! sourceService={}, eventType={}. Please investigate origin service data hygiene.",
                        sourceService, eventType);
            }

            return objectMapper.writeValueAsString(sanitizedRoot);
        } catch (Exception e) {
            log.warn("Failed to parse JSON for redaction, returning sanitized fallback: {}", e.getMessage());
            return "{\"sanitization_error\":\"Unable to parse raw payload\"}";
        }
    }

    private JsonNode traverseAndRedact(JsonNode node, AtomicBoolean redactionTriggered) {
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();

            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String fieldName = entry.getKey();
                JsonNode child = entry.getValue();

                if (isSensitiveField(fieldName)) {
                    objectNode.set(fieldName, new TextNode(REDACTED_PLACEHOLDER));
                    redactionTriggered.set(true);
                } else if (child.isContainerNode()) {
                    traverseAndRedact(child, redactionTriggered);
                }
            }
            return objectNode;
        } else if (node.isArray()) {
            ArrayNode arrayNode = (ArrayNode) node;
            for (int i = 0; i < arrayNode.size(); i++) {
                JsonNode element = arrayNode.get(i);
                if (element.isContainerNode()) {
                    traverseAndRedact(element, redactionTriggered);
                }
            }
            return arrayNode;
        }
        return node;
    }

    private boolean isSensitiveField(String fieldName) {
        if (fieldName == null) return false;
        String normalized = fieldName.toLowerCase().replace("-", "").replace("_", "");
        return SENSITIVE_FIELDS.contains(normalized) || SENSITIVE_FIELDS.contains(fieldName.toLowerCase());
    }
}
