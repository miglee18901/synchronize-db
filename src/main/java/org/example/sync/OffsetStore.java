package org.example.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Persists the last successfully scanned RBT_LOG ID as one plain-text number. */
public final class OffsetStore {
    private final Path path;

    public OffsetStore(Path path) {
        this.path = path;
    }

    public long read() throws IOException {
        if (!Files.exists(path)) {
            return 0L;
        }
        String value = new String(Files.readAllBytes(path), StandardCharsets.UTF_8).trim();
        if (value.isEmpty()) {
            return 0L;
        }
        try {
            long offset = Long.parseLong(value);
            if (offset < 0) {
                throw new IOException("Offset must not be negative: " + path);
            }
            return offset;
        } catch (NumberFormatException exception) {
            throw new IOException("Invalid offset in " + path + ": " + value, exception);
        }
    }

    public void write(long offset) throws IOException {
        if (offset < 0) {
            throw new IllegalArgumentException("Offset must not be negative");
        }
        Path parent = path.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(path, String.valueOf(offset).getBytes(StandardCharsets.UTF_8));
    }
}
