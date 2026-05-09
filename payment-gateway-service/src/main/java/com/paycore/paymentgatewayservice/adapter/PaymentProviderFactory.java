package com.paycore.paymentgatewayservice.adapter;

import com.paycore.paymentgatewayservice.domain.enums.PaymentProvider;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class PaymentProviderFactory {

    private final Map<PaymentProvider, PaymentProviderAdapter> adapterMap = new EnumMap<>(PaymentProvider.class);

    public PaymentProviderFactory(List<PaymentProviderAdapter> adapters) {
        for (PaymentProviderAdapter adapter : adapters) {
            adapterMap.put(adapter.getProvider(), adapter);
        }
    }

    public PaymentProviderAdapter getAdapter(PaymentProvider provider) {
        PaymentProviderAdapter adapter = adapterMap.get(provider);
        if (adapter == null) {
            throw new IllegalArgumentException("Unsupported payment provider: " + provider);
        }
        return adapter;
    }
}
