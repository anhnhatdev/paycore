package com.paycore.auditservice.service;

import com.paycore.auditservice.dto.ChainVerificationResult;

public interface ChainVerificationService {

    /**
     * Re-computes SHA-256 hash chains between sequence numbers and verifies integrity.
     */
    ChainVerificationResult verifyChain(Long fromSeq, Long toSeq);
}
