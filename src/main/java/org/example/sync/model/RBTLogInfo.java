package org.example.sync.model;

public final class RBTLogInfo {
    private final long id;
    private final String toneId;
    private final String toneCode;
    private final int actionType;
    private final String filePath;
    private final String toneName;
    private final String singer;
    private final String cpCode;
    private final String actionAccount;
    private final String expirationDate;
    private final String description;

    public RBTLogInfo(long id, String toneId, String toneCode, int actionType, String filePath,
                      String toneName, String singer, String cpCode, String actionAccount,
                      String expirationDate, String description) {
        this.id = id;
        this.toneId = toneId;
        this.toneCode = toneCode;
        this.actionType = actionType;
        this.filePath = filePath;
        this.toneName = toneName;
        this.singer = singer;
        this.cpCode = cpCode;
        this.actionAccount = actionAccount;
        this.expirationDate = expirationDate;
        this.description = description;
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

    public String getFilePath() {
        return filePath;
    }

    public String getToneName() {
        return toneName;
    }

    public String getSinger() {
        return singer;
    }

    public String getCpCode() {
        return cpCode;
    }

    public String getActionAccount() {
        return actionAccount;
    }

    public String getExpirationDate() {
        return expirationDate;
    }

    public String getDescription() {
        return description;
    }

}
