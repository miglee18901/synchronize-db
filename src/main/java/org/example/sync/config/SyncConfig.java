package org.example.sync.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

public final class SyncConfig {
    private final int batchSize;
    private final long delayTimeMillis;
    private final long periodTimeMillis;
    private final List<String> serverIpWhitelist;

    private SyncConfig(int batchSize, long delayTimeMillis, long periodTimeMillis, List<String> serverIpWhitelist) {
        this.batchSize = batchSize;
        this.delayTimeMillis = delayTimeMillis;
        this.periodTimeMillis = periodTimeMillis;
        this.serverIpWhitelist = Collections.unmodifiableList(new ArrayList<>(serverIpWhitelist));
    }

    public static SyncConfig load(File file) throws IOException {
        Properties properties = new Properties();
        try (FileInputStream input = new FileInputStream(file)) {
            properties.load(input);
        }
        int batchSize;
        try {
            batchSize = Integer.parseInt(properties.getProperty("BATCH_SIZE", "1000").trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("BATCH_SIZE must be a positive integer", exception);
        }
        if (batchSize <= 0) {
            throw new IllegalArgumentException("BATCH_SIZE must be a positive integer");
        }
        long delayTimeMillis;
        try {
            delayTimeMillis = Long.parseLong(properties.getProperty("DELAY_TIME", "3000").trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("DELAY_TIME must be a positive integer number of milliseconds", exception);
        }
        if (delayTimeMillis <= 0) {
            throw new IllegalArgumentException("DELAY_TIME must be a positive integer number of milliseconds");
        }
        long periodTimeMillis;
        try {
            periodTimeMillis = Long.parseLong(properties.getProperty("PERIOD_TIME", "300000").trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("PERIOD_TIME must be a positive integer number of milliseconds", exception);
        }
        if (periodTimeMillis <= 0) {
            throw new IllegalArgumentException("PERIOD_TIME must be a positive integer number of milliseconds");
        }
        String whitelistValue = properties.getProperty("SERVER_IP_WHITELIST", "").trim();
        if (whitelistValue.isEmpty()) {
            throw new IllegalArgumentException("SERVER_IP_WHITELIST must contain at least one IP address");
        }
        List<String> serverIpWhitelist = new ArrayList<>();
        for (String value : whitelistValue.split(",")) {
            String ip = value.trim();
            if (!ip.isEmpty() && !serverIpWhitelist.contains(ip)) {
                serverIpWhitelist.add(ip);
            }
        }
        if (serverIpWhitelist.isEmpty()) {
            throw new IllegalArgumentException("SERVER_IP_WHITELIST must contain at least one IP address");
        }
        return new SyncConfig(batchSize, delayTimeMillis, periodTimeMillis, serverIpWhitelist);
    }

    public int getBatchSize() {
        return batchSize;
    }

    public long getDelayTimeMillis() {
        return delayTimeMillis;
    }

    public long getPeriodTimeMillis() {
        return periodTimeMillis;
    }

    public List<String> getServerIpWhitelist() {
        return serverIpWhitelist;
    }

}
