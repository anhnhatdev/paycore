package com.paycore.auditservice.repository;

import com.paycore.auditservice.domain.entity.AuditAccessLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface AuditAccessLogRepository extends JpaRepository<AuditAccessLog, UUID> {

    Page<AuditAccessLog> findAllByOrderByAccessedAtDesc(Pageable pageable);
}
