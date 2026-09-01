package br.com.pedrodalben.easyvip.webstore.model;

import java.util.ArrayList;
import java.util.List;

public class FulfillmentRecord {

    private String fulfillmentId;
    private String orderId;
    private String originServerId;
    private String minecraftUuid;
    private String minecraftUsername;
    private String payloadDigest;
    private String status;
    private String requestKeyId;
    private String claimToken;
    private long createdAt;
    private Long claimedAt;
    private Long leaseExpiresAt;
    private Long completedAt;
    private Long failedAt;
    private String failureCode;
    private String errorMessage;
    private long updatedAt;

    private final List<FulfillmentItemRecord> items = new ArrayList<>();

    public String getFulfillmentId() { return fulfillmentId; }
    public void setFulfillmentId(String v) { this.fulfillmentId = v; }

    public String getOrderId() { return orderId; }
    public void setOrderId(String v) { this.orderId = v; }

    public String getOriginServerId() { return originServerId; }
    public void setOriginServerId(String v) { this.originServerId = v; }

    /**
     * Legacy alias retained for older call sites.
     */
    public String getServerId() { return originServerId; }

    /**
     * Legacy alias retained for older call sites.
     */
    public void setServerId(String v) { this.originServerId = v; }

    public String getMinecraftUuid() { return minecraftUuid; }
    public void setMinecraftUuid(String v) { this.minecraftUuid = v; }

    public String getMinecraftUsername() { return minecraftUsername; }
    public void setMinecraftUsername(String v) { this.minecraftUsername = v; }

    public String getPayloadDigest() { return payloadDigest; }
    public void setPayloadDigest(String v) { this.payloadDigest = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public String getRequestKeyId() { return requestKeyId; }
    public void setRequestKeyId(String v) { this.requestKeyId = v; }

    public String getClaimToken() { return claimToken; }
    public void setClaimToken(String v) { this.claimToken = v; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long v) { this.createdAt = v; }

    public Long getClaimedAt() { return claimedAt; }
    public void setClaimedAt(Long v) { this.claimedAt = v; }

    public Long getLeaseExpiresAt() { return leaseExpiresAt; }
    public void setLeaseExpiresAt(Long v) { this.leaseExpiresAt = v; }

    public Long getCompletedAt() { return completedAt; }
    public void setCompletedAt(Long v) { this.completedAt = v; }

    public Long getFailedAt() { return failedAt; }
    public void setFailedAt(Long v) { this.failedAt = v; }

    public String getFailureCode() { return failureCode; }
    public void setFailureCode(String v) { this.failureCode = v; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String v) { this.errorMessage = v; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long v) { this.updatedAt = v; }

    public List<FulfillmentItemRecord> getItems() { return items; }
}
