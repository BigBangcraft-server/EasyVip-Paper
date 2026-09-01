package br.com.pedrodalben.easyvip.webstore.model;

public class FulfillmentItemRecord {

    private String lineItemId;
    private String fulfillmentId;
    private String productSku;
    private int quantity;
    private String keyCode;
    private String keyFingerprint;
    private String status;
    private long createdAt;
    private long updatedAt;

    public String getLineItemId() { return lineItemId; }
    public void setLineItemId(String v) { this.lineItemId = v; }

    public String getFulfillmentId() { return fulfillmentId; }
    public void setFulfillmentId(String v) { this.fulfillmentId = v; }

    public String getProductSku() { return productSku; }
    public void setProductSku(String v) { this.productSku = v; }

    public int getQuantity() { return quantity; }
    public void setQuantity(int v) { this.quantity = v; }

    public String getKeyCode() { return keyCode; }
    public void setKeyCode(String v) { this.keyCode = v; }

    public String getKeyFingerprint() { return keyFingerprint; }
    public void setKeyFingerprint(String v) { this.keyFingerprint = v; }

    public String getStatus() { return status; }
    public void setStatus(String v) { this.status = v; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long v) { this.createdAt = v; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long v) { this.updatedAt = v; }
}
