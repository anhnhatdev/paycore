package com.paycore.accountservice.dto.request;

import jakarta.validation.constraints.*;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "Email is required")
    @Email(message = "Email format is invalid")
    private String email;

    /**
     * Fintech password policy: min 8 chars, uppercase + lowercase + digit + special char.
     */
    @NotBlank(message = "Password is required")
    @Pattern(
        regexp = "^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)(?=.*[@$!%*?&_\\-#])[A-Za-z\\d@$!%*?&_\\-#]{8,}$",
        message = "Password must be at least 8 characters and contain uppercase, lowercase, digit, and special character"
    )
    private String password;

    @NotBlank(message = "Full name is required")
    @Size(max = 255, message = "Full name must not exceed 255 characters")
    private String fullName;

    @Pattern(
        regexp = "^0\\d{9}$",
        message = "Phone number must be a valid Vietnamese format (10 digits starting with 0)"
    )
    private String phoneNumber;
}
