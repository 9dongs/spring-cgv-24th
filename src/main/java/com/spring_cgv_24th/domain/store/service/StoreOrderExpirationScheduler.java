package com.spring_cgv_24th.domain.store.service;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@RequiredArgsConstructor
@ConditionalOnProperty(name = "store.order.expiration-enabled", havingValue = "true", matchIfMissing = true)
public class StoreOrderExpirationScheduler {

    private final StoreService storeService;

    @Scheduled(fixedDelay = 30_000)
    public void expireOrders() {
        storeService.expirePendingOrders();
    }
}
