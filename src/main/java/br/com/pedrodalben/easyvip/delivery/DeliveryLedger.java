package br.com.pedrodalben.easyvip.delivery;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** Small orchestration facade over the durable SQL delivery ledger. */
public interface DeliveryLedger {
    DeliveryClaim claim(DeliveryRequest request, String nodeId, long leaseMillis, Clock clock);

    boolean complete(String deliveryId, UUID playerUuid, String nodeId, Clock clock);

    boolean fail(String deliveryId, UUID playerUuid, String nodeId, String failureCode, Clock clock);

    static DeliveryLedger sql() {
        return new SqlDeliveryLedger();
    }

    static void requireNode(String nodeId) {
        if (nodeId == null || nodeId.isBlank()) throw new IllegalArgumentException("nodeId cannot be blank");
    }
}
