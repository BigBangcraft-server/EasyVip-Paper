package br.com.pedrodalben.easyvip.model;

import java.util.UUID;

public class AuditLogRecord {
    private UUID id;
    private long timestamp;
    private String operator;
    private String action;
    private String details;

    public AuditLogRecord() {
    }

    public AuditLogRecord(String operator, String action, String details) {
        this.id = UUID.randomUUID();
        this.timestamp = System.currentTimeMillis();
        this.operator = operator;
        this.action = action;
        this.details = details;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getOperator() {
        return operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getDetails() {
        return details;
    }

    public void setDetails(String details) {
        this.details = details;
    }
}
