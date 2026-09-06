package org.example.sync.model;

public final class RBTLogInfo {
    private final long id;
    private final String toneCode;
    private final int actionType;

    public RBTLogInfo(long id, String toneCode, int actionType) {
        this.id = id;
        this.toneCode = toneCode;
        this.actionType = actionType;
    }

    public long getId() {
        return id;
    }

    public String getToneCode() {
        return toneCode;
    }

    public int getActionType() {
        return actionType;
    }
}
