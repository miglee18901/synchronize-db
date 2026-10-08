package org.example.sync.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

public final class SyncConfig {
    private final int batchSize;
    private final long delayTimeMillis;
    private final long periodTimeMillis;
    private final List<String> serverIpWhitelist;
    private final Path tempDirectory;
    private final Path wavDirectory;
    private final Path tempMusicDirectory;
    private final Path amrDirectory;

    private SyncConfig(int batchSize, long delayTimeMillis, long periodTimeMillis, List<String> serverIpWhitelist,
                       Path tempDirectory, Path wavDirectory, Path tempMusicDirectory, Path amrDirectory) {
        this.batchSize = batchSize;
        this.delayTimeMillis = delayTimeMillis;
        this.periodTimeMillis = periodTimeMillis;
        this.serverIpWhitelist = Collections.unmodifiableList(new ArrayList<>(serverIpWhitelist));
        this.tempDirectory = tempDirectory;
        this.wavDirectory = wavDirectory;
        this.tempMusicDirectory = tempMusicDirectory;
        this.amrDirectory = amrDirectory;
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
        Path tempDirectory = directory(properties, "TEMP", "/u03/temp");
        Path wavDirectory = directory(properties, "WAV", "/u03/wav");
        Path tempMusicDirectory = directory(properties, "TEMP_MUSIC", "/u03/mp3");
        Path amrDirectory = directory(properties, "AMR", "/u03/amr");
        return new SyncConfig(batchSize, delayTimeMillis, periodTimeMillis, serverIpWhitelist,
                tempDirectory, wavDirectory, tempMusicDirectory, amrDirectory);
    }

    private static Path directory(Properties properties, String key, String defaultValue) {
        String value = properties.getProperty(key, "").trim();
        if (value.isEmpty()) {
            value = defaultValue;
        }
        return Paths.get(value).toAbsolutePath().normalize();
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

    public Path getTempDirectory() {
        return tempDirectory;
    }

    public Path getWavDirectory() {
        return wavDirectory;
    }

    public Path getTempMusicDirectory() {
        return tempMusicDirectory;
    }

    public Path getAmrDirectory() {
        return amrDirectory;
    }

}
