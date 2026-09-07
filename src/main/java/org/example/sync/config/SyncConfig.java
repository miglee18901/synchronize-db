package org.example.sync.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

public final class SyncConfig {
    private final int batchSize;
    private final long delayTimeMillis;
    private final long periodTimeMillis;
    private final File reportDirectory;

    private SyncConfig(int batchSize, long delayTimeMillis, long periodTimeMillis, File reportDirectory) {
        this.batchSize = batchSize;
        this.delayTimeMillis = delayTimeMillis;
        this.periodTimeMillis = periodTimeMillis;
        this.reportDirectory = reportDirectory;
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
            delayTimeMillis = Long.parseLong(properties.getProperty("DELAY_TIME", "").trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("DELAY_TIME must be a positive integer number of milliseconds", exception);
        }
        if (delayTimeMillis <= 0) {
            throw new IllegalArgumentException("DELAY_TIME must be a positive integer number of milliseconds");
        }
        long periodTimeMillis;
        try {
            periodTimeMillis = Long.parseLong(properties.getProperty("PERIOD_TIME", "").trim());
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("PERIOD_TIME must be a positive integer number of milliseconds", exception);
        }
        if (periodTimeMillis <= 0) {
            throw new IllegalArgumentException("PERIOD_TIME must be a positive integer number of milliseconds");
        }
        String reportPath = properties.getProperty("ERROR_REPORT_DIRECTORY", "result").trim();
        if (reportPath.isEmpty()) {
            throw new IllegalArgumentException("ERROR_REPORT_DIRECTORY must not be empty");
        }
        return new SyncConfig(batchSize, delayTimeMillis, periodTimeMillis, new File(reportPath));
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

    public File getReportDirectory() {
        return reportDirectory;
    }

}
