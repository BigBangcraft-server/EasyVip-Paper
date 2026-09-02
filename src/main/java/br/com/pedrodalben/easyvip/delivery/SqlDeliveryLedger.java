package br.com.pedrodalben.easyvip.delivery;

import br.com.pedrodalben.easyvip.persistence.SqlDatabaseManager;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/** SQL-backed durable ledger; uniqueness and lease ownership are database decisions. */
public final class SqlDeliveryLedger implements DeliveryLedger {
    @Override
    public DeliveryClaim claim(DeliveryRequest request, String nodeId, long leaseMillis, Clock clock) {
        Objects.requireNonNull(request, "request");
        DeliveryLedger.requireNode(nodeId);
        Objects.requireNonNull(clock, "clock");
        if (leaseMillis < 1_000L) throw new IllegalArgumentException("leaseMillis must be at least 1000");
        SqlDatabaseManager.DeliveryClaimResult result = SqlDatabaseManager.claimDelivery(
                request, nodeId, clock.millis(), leaseMillis);
        return new DeliveryClaim(result.status(), result.deliveryId(), result.attempts(),
                result.leaseExpiresAt(), result.failureCode());
    }

    @Override
    public boolean complete(String deliveryId, UUID playerUuid, String nodeId, Clock clock) {
        Objects.requireNonNull(clock, "clock");
        DeliveryLedger.requireNode(nodeId);
        return SqlDatabaseManager.completeDelivery(deliveryId, playerUuid, nodeId, clock.millis());
    }

    @Override
    public boolean fail(String deliveryId, UUID playerUuid, String nodeId, String failureCode, Clock clock) {
        Objects.requireNonNull(clock, "clock");
        DeliveryLedger.requireNode(nodeId);
        return SqlDatabaseManager.failDelivery(deliveryId, playerUuid, nodeId, failureCode, clock.millis());
    }
}
