package org.example.sync;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Properties;

/**
 * Configuration shared by the scheduler and one synchronization run.
 */
public final class SyncConfig {
    private final int batchSize;
    private final String cronExpression;
    private final File reportDirectory;

    private SyncConfig(int batchSize, String cronExpression, File reportDirectory) {
        this.batchSize = batchSize;
        this.cronExpression = cronExpression;
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
        String cron = properties.getProperty("CRONJOB", "").trim();
        String reportPath = properties.getProperty("PATH_STATISTICS_FILE", "result").trim();
        if (reportPath.isEmpty()) {
            throw new IllegalArgumentException("PATH_STATISTICS_FILE must not be empty");
        }
        return new SyncConfig(batchSize, cron, new File(reportPath));
    }

    public int getBatchSize() {
        return batchSize;
    }

    public String getCronExpression() {
        return cronExpression;
    }

    public File getReportDirectory() {
        return reportDirectory;
    }
}
