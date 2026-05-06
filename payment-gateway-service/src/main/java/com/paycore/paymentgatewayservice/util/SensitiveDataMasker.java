package com.paycore.paymentgatewayservice.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.extern.slf4j.Slf4j;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public final class SensitiveDataMasker {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Set<String> SENSITIVE_KEYS = Set.of(
            "cardnumber", "card_number", "pan", "accountnumber", "bankaccountnumber",
            "cvv", "cvc", "securitycode", "security_code", "pin", "password",
            "secret", "vnp_hashsecret", "secretkey", "accesskey", "apikey"
    );

    // Regex for credit card PAN (13 to 19 digits with optional spaces or dashes)
    private static final Pattern CARD_PATTERN = Pattern.compile("\\b(?:\\d[ -]*?){13,19}\\b");

    private SensitiveDataMasker() {
        // utility class
    }

    /**
     * Sanitizes a JSON string or raw text payload by masking sensitive fields according to PCI-DSS standards.
     */
    public static String mask(String payload) {
        if (payload == null || payload.isBlank()) {
            return payload;
        }

        try {
            JsonNode rootNode = OBJECT_MAPPER.readTree(payload);
            maskJsonNode(rootNode);
            return OBJECT_MAPPER.writeValueAsString(rootNode);
        } catch (Exception e) {
            // If not JSON, apply regex-based card and secret masking
            return maskRawText(payload);
        }
    }

    private static void maskJsonNode(JsonNode node) {
        if (node.isObject()) {
            ObjectNode objectNode = (ObjectNode) node;
            Iterator<Map.Entry<String, JsonNode>> fields = objectNode.fields();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                String key = entry.getKey().toLowerCase().replace("-", "").replace("_", "");
                JsonNode value = entry.getValue();

                if (SENSITIVE_KEYS.contains(key)) {
                    if (key.contains("card") || key.contains("pan")) {
                        objectNode.put(entry.getKey(), maskCardNumber(value.asText()));
                    } else if (key.contains("cvv") || key.contains("cvc") || key.contains("pin")) {
                        objectNode.put(entry.getKey(), "***");
                    } else {
                        objectNode.put(entry.getKey(), "******");
                    }
                } else if (value.isContainerNode()) {
                    maskJsonNode(value);
                } else if (value.isTextual()) {
                    String maskedText = maskRawText(value.asText());
                    if (!maskedText.equals(value.asText())) {
                        objectNode.put(entry.getKey(), maskedText);
                    }
                }
            }
        } else if (node.isArray()) {
            for (JsonNode item : node) {
                maskJsonNode(item);
            }
        }
    }

    /**
     * Masks raw text containing credit cards or key-value query strings.
     */
    public static String maskRawText(String text) {
        if (text == null || text.isBlank()) {
            return text;
        }

        Matcher matcher = CARD_PATTERN.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String card = matcher.group();
            matcher.appendReplacement(sb, maskCardNumber(card));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * Formats card number: keeps first 4 and last 4 digits, masks the rest with asterisks.
     */
    public static String maskCardNumber(String card) {
        if (card == null) {
            return null;
        }
        String clean = card.replaceAll("[\\s-]", "");
        if (clean.length() < 8) {
            return "******";
        }
        String first4 = clean.substring(0, 4);
        String last4 = clean.substring(clean.length() - 4);
        int maskedLength = clean.length() - 8;
        return first4 + "*".repeat(maskedLength) + last4;
    }
}
