package com.paycore.notificationservice.repository;

import com.paycore.notificationservice.domain.entity.NotificationPreference;
import com.paycore.notificationservice.domain.entity.NotificationPreferenceId;
import com.paycore.notificationservice.domain.enums.NotificationChannel;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, NotificationPreferenceId> {

    List<NotificationPreference> findByIdUserId(UUID userId);

    Optional<NotificationPreference> findByIdUserIdAndIdEventTypeAndIdChannel(
            UUID userId,
            String eventType,
            NotificationChannel channel
    );
}
