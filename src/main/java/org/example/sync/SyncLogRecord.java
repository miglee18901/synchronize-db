package org.example.sync;

final class SyncLogRecord {
    private final long id;
    private final String toneCode;
    private final int actionType;

    SyncLogRecord(long id, String toneCode, int actionType) {
        this.id = id;
        this.toneCode = toneCode;
        this.actionType = actionType;
    }

    long getId() {
        return id;
    }

    String getToneCode() {
        return toneCode;
    }

    int getActionType() {
        return actionType;
    }
}
