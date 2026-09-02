package br.com.pedrodalben.easyvip.delivery;

import java.util.Objects;

/** Result of a durable claim; external actions remain at-least-once. */
public record DeliveryClaim(DeliveryStatus status, String deliveryId, int attempts,
                            long leaseExpiresAt, String failureCode) {
    public DeliveryClaim {
        Objects.requireNonNull(status, "status");
        if (attempts < 0) throw new IllegalArgumentException("attempts cannot be negative");
        if (leaseExpiresAt < 0) throw new IllegalArgumentException("leaseExpiresAt cannot be negative");
    }

    public boolean acquired() { return status == DeliveryStatus.CLAIMED; }
    public boolean delivered() { return status == DeliveryStatus.DELIVERED; }
}
