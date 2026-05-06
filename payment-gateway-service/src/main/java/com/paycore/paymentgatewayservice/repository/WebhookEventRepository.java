package com.paycore.paymentgatewayservice.repository;

import com.paycore.paymentgatewayservice.domain.entity.WebhookEvent;
import com.paycore.paymentgatewayservice.domain.enums.PaymentProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface WebhookEventRepository extends JpaRepository<WebhookEvent, UUID> {

    Optional<WebhookEvent> findByProviderAndProviderEventId(PaymentProvider provider, String providerEventId);

    boolean existsByProviderAndProviderEventId(PaymentProvider provider, String providerEventId);
}
