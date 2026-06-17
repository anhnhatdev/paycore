package com.paycore.notificationservice.provider;

import com.paycore.notificationservice.domain.enums.NotificationChannel;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class NotificationProviderRegistry {

    private final Map<NotificationChannel, NotificationProvider> providers = new EnumMap<>(NotificationChannel.class);

    public NotificationProviderRegistry(List<NotificationProvider> providerList) {
        for (NotificationProvider provider : providerList) {
            providers.put(provider.getChannel(), provider);
        }
    }

    public Optional<NotificationProvider> getProvider(NotificationChannel channel) {
        return Optional.ofNullable(providers.get(channel));
    }
}
