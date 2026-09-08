package org.example.sync.model;

public final class RBTLogInfo {
    private final long id;
    private final String toneId;
    private final String toneCode;
    private final int actionType;
    private final String server;

    public RBTLogInfo(long id, String toneId, String toneCode, int actionType, String server) {
        this.id = id;
        this.toneId = toneId;
        this.toneCode = toneCode;
        this.actionType = actionType;
        this.server = server;
    }

    public long getId() {
        return id;
    }

    public String getToneCode() {
        return toneCode;
    }

    public String getToneId() {
        return toneId;
    }

    public int getActionType() {
        return actionType;
    }

    public String getServer() {
        return server;
    }
}
