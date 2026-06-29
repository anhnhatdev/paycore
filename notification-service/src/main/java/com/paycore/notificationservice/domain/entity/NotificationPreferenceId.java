package com.paycore.notificationservice.domain.entity;

import com.paycore.notificationservice.domain.enums.NotificationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import lombok.*;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationPreferenceId implements Serializable {

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "event_type", length = 50, nullable = false)
    private String eventType;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", length = 10, nullable = false)
    private NotificationChannel channel;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        NotificationPreferenceId that = (NotificationPreferenceId) o;
        return Objects.equals(userId, that.userId) &&
                Objects.equals(eventType, that.eventType) &&
                channel == that.channel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(userId, eventType, channel);
    }
}
