package org.example.sync.scheduler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class OffsetStore {
    private static final Logger logger = LogManager.getLogger(OffsetStore.class);
    private final Path path;

    public OffsetStore(Path path) {
        this.path = path;
    }

    public long read() throws IOException {
        logger.debug("[OFFSET] Reading offset from {}", path.toAbsolutePath().normalize());
        if (!Files.exists(path)) {
            logger.debug("[OFFSET] Offset file does not exist; using 0");
            return 0L;
        }
        String value = new String(Files.readAllBytes(path.toAbsolutePath().normalize().toFile().getCanonicalFile().toPath()), StandardCharsets.UTF_8).trim();
        if (value.isEmpty()) {
            logger.debug("[OFFSET] Offset file is empty; using 0");
            return 0L;
        }
        try {
            long offset = Long.parseLong(value);
            if (offset < 0) {
                throw new IOException("Offset must not be negative: " + path);
            }
            logger.debug("[OFFSET] Offset value loaded: {}", offset);
            return offset;
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid offset in " + path + ": " + value, exception);
        }
    }

    public void write(long offset) throws IOException {
        if (offset < 0) {
            throw new IllegalArgumentException("Offset must not be negative");
        }
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        logger.debug("[OFFSET] Writing offset {} to {}", offset, path.toAbsolutePath().normalize());
        Files.write(path, String.valueOf(offset).getBytes(StandardCharsets.UTF_8));
        logger.debug("[OFFSET] Offset persisted: {}", offset);
    }
}
