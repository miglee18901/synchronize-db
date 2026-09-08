package org.example.sync.model;

public final class RBTLogInfo {
    private final long id;
    private final String toneId;
    private final String toneCode;
    private final int actionType;

    public RBTLogInfo(long id, String toneId, String toneCode, int actionType) {
        this.id = id;
        this.toneId = toneId;
        this.toneCode = toneCode;
        this.actionType = actionType;
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

}
