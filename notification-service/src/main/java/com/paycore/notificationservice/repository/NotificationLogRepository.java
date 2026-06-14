package com.paycore.notificationservice.repository;

import com.paycore.notificationservice.domain.entity.NotificationLog;
import com.paycore.notificationservice.domain.enums.NotificationStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

    List<NotificationLog> findByUserIdOrderByCreatedAtDesc(UUID userId);

    Optional<NotificationLog> findByEventId(UUID eventId);

    List<NotificationLog> findByStatusAndAttemptCountLessThanOrderByCreatedAtAsc(
            NotificationStatus status,
            int maxAttempts,
            Pageable pageable
    );

    @Query("SELECT n FROM NotificationLog n WHERE n.status = 'PENDING' AND n.createdAt < :stuckBefore ORDER BY n.createdAt ASC")
    List<NotificationLog> findStuckPendingNotifications(@Param("stuckBefore") Instant stuckBefore, Pageable pageable);
}
