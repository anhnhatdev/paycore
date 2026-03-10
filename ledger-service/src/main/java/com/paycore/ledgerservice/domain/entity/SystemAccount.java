package com.paycore.ledgerservice.domain.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.UUID;

/**
 * System account entity representing internal and external counterparty accounts (e.g. SUSPENSE_VND).
 */
@Entity
@Table(name = "system_accounts")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemAccount {

    @Id
    @Column(name = "id", nullable = false)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 50)
    private String code;

    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "description")
    private String description;
}
