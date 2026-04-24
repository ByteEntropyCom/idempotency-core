package com.byteentropy.idempotency_core.model;

import java.io.Serializable;

public class IdempotencyRecord implements Serializable {
    private IdempotencyStatus status;
    private Object response;
    private String requestHash;
    private long timestamp;

    public IdempotencyRecord() {}

    public IdempotencyRecord(IdempotencyStatus status, Object response, String requestHash, long timestamp) {
        this.status = status;
        this.response = response;
        this.requestHash = requestHash;
        this.timestamp = timestamp;
    }

    // Getters
    public IdempotencyStatus getStatus() { return status; }
    public Object getResponse() { return response; }
    public String getRequestHash() { return requestHash; }
    public long getTimestamp() { return timestamp; }

    // Setters
    public void setStatus(IdempotencyStatus status) { this.status = status; }
    public void setResponse(Object response) { this.response = response; }
    public void setRequestHash(String requestHash) { this.requestHash = requestHash; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private IdempotencyStatus status;
        private Object response;
        private String requestHash;
        private long timestamp;

        public Builder status(IdempotencyStatus status) { this.status = status; return this; }
        public Builder response(Object response) { this.response = response; return this; }
        public Builder requestHash(String requestHash) { this.requestHash = requestHash; return this; }
        public Builder timestamp(long timestamp) { this.timestamp = timestamp; return this; }

        public IdempotencyRecord build() {
            return new IdempotencyRecord(status, response, requestHash, timestamp);
        }
    }
}