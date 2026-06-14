package com.paycore.notificationservice.util;

public final class RecipientMasker {

    private RecipientMasker() {}

    /**
     * Masks an email address to protect PII.
     * e.g. "john.doe@example.com" -> "j*****e@example.com"
     * e.g. "user@gmail.com" -> "u**r@gmail.com"
     * e.g. "ab@c.com" -> "a*@c.com"
     */
    public static String maskEmail(String email) {
        if (email == null || email.isBlank()) {
            return "***";
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return maskGeneral(email);
        }

        String localPart = email.substring(0, atIndex);
        String domainPart = email.substring(atIndex);

        if (localPart.length() <= 2) {
            return localPart.charAt(0) + "*" + domainPart;
        }

        char firstChar = localPart.charAt(0);
        char lastChar = localPart.charAt(localPart.length() - 1);
        int asterisksCount = Math.max(localPart.length() - 2, 2);
        String asterisks = "*".repeat(asterisksCount);

        return firstChar + asterisks + lastChar + domainPart;
    }

    /**
     * Masks a phone number to protect PII.
     * e.g. "0901234567" -> "090***4567"
     * e.g. "+84901234567" -> "+849***4567"
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "***";
        }
        String cleanPhone = phone.trim();
        if (cleanPhone.length() <= 6) {
            return "***" + cleanPhone.substring(Math.max(0, cleanPhone.length() - 2));
        }

        int prefixLen = cleanPhone.startsWith("+") ? 4 : 3;
        int suffixLen = 4;

        if (cleanPhone.length() <= prefixLen + suffixLen) {
            return cleanPhone.substring(0, 2) + "***" + cleanPhone.substring(cleanPhone.length() - 2);
        }

        String prefix = cleanPhone.substring(0, prefixLen);
        String suffix = cleanPhone.substring(cleanPhone.length() - suffixLen);
        return prefix + "***" + suffix;
    }

    /**
     * Generic mask for device tokens or identifiers.
     */
    public static String maskGeneral(String input) {
        if (input == null || input.isBlank()) {
            return "***";
        }
        if (input.length() <= 4) {
            return "****";
        }
        return input.substring(0, 2) + "***" + input.substring(input.length() - 2);
    }
}
