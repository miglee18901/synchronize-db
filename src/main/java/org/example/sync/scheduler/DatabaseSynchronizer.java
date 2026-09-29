package org.example.sync.scheduler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.example.sync.copy.DatabaseRowCopier;
import org.example.sync.copy.SchemaCopyPlan;
import org.example.sync.model.RBTLogInfo;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.SQLQuery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public final class DatabaseSynchronizer {
    private static final Logger logger = LogManager.getLogger(DatabaseSynchronizer.class);
    private static final String[] ACTION_15_DELETE_TABLES = {
            SchemaCopyPlan.MAP_CP_RBT, SchemaCopyPlan.TONELIST,
            "SPECIAL_TONELIST", "TOP_HOT", "TOP_MONTH", "TOP_WEEK", "TONE_TOPIC",
            "RBT_SYNTAX_REPRESENT", "TONE_SMS_SYNTAX", "INTRO_RBT_CONFIG",
            "MAP_CATEGORY_RBT_HOT", "CORP_RBT", "CORP_MSISDN_ACTION", "TONE_CATEGORY"
    };
    private static final String TONE_CATEGORY = "TONE_CATEGORY";
    private final DatabaseRowCopier copier = new DatabaseRowCopier();
    private final Path tempDirectory;
    private final Path wavDirectory;
    private final Path tempMusicDirectory;
    private final Path amrDirectory;

    public DatabaseSynchronizer(Path tempDirectory, Path wavDirectory, Path tempMusicDirectory, Path amrDirectory) {
        this.tempDirectory = normalizeDirectory(tempDirectory, "TEMP");
        this.wavDirectory = normalizeDirectory(wavDirectory, "WAV");
        this.tempMusicDirectory = normalizeDirectory(tempMusicDirectory, "TEMP_MUSIC");
        this.amrDirectory = normalizeDirectory(amrDirectory, "AMR");
    }

    private static Path normalizeDirectory(Path directory, String name) {
        if (directory == null) {
            throw new IllegalArgumentException(name + " directory must not be null");
        }
        return directory.toAbsolutePath().normalize();
    }

    public void synchronize(SessionFactory target16M, SessionFactory source21M, int batchSize, OffsetStore offsetStore,
                            List<String> serverIpWhitelist, SchemaCopyPlan copyPlan) throws IOException {
        long startedAt = System.currentTimeMillis();
        logger.debug("[SYNC] Starting synchronization cycle: batchSize={}, serverWhitelist={}",
                batchSize, serverIpWhitelist);
        long lastId = offsetStore.read();
        logger.debug("[SYNC] Offset loaded: {}", lastId);
        int scanned = 0;
        int copied = 0;
        int errors = 0;
        logger.debug("[SYNC] Opening source and target sessions");
        Session target = target16M.openSession();
        Session source = source21M.openSession();
        try {
            List<RBTLogInfo> batch;
            try {
                logger.debug("[SYNC] Loading RBT_LOG batch after offset {}", lastId);
                batch = loadBatch(source, lastId, batchSize, serverIpWhitelist);
                logger.debug("[SYNC] Batch loaded: recordCount={}, offset={}", batch.size(), lastId);
            } catch (SQLException exception) {
                throw new IOException("Unable to read RBT_LOG after ID " + lastId, exception);
            }
            logger.debug("[SYNC] Processing {} record(s)", batch.size());
            for (RBTLogInfo record : batch) {
                scanned++;
                lastId = record.getId();
                long recordStartedAt = System.currentTimeMillis();
                logger.debug("[RECORD] Start: logId={}, toneId={}, toneCode={}, actionType={}, filePath={}",
                        record.getId(), record.getToneId(), record.getToneCode(), record.getActionType(), record.getFilePath());
                try {
                    int recordCopied = process(target, source, record, copyPlan);
                    logger.debug("[RECORD] Business data processed: logId={}, affectedRows={}; committing transaction",
                            record.getId(), recordCopied);
                    target.connection().commit();
                    copied += recordCopied;
                    writeSyncLog(target16M, record, "success", 1);
                    logger.debug("[RECORD] Success: logId={}, affectedRows={}, durationMillis={}",
                            record.getId(), recordCopied, System.currentTimeMillis() - recordStartedAt);
                } catch (Exception exception) {
                    errors++;
                    logger.debug("[RECORD] Rolling back failed record: logId={}", record.getId());
                    rollback(target.connection(), exception);
                    logger.error("Synchronization failed for RBT_LOG ID {} (toneCode={}, actionType={}): {}",
                            record.getId(), record.getToneCode(), record.getActionType(), exception.getMessage(), exception);
                    writeSyncLog(target16M, record, exception.getMessage(), 0);
                }
            }
        } finally {
            logger.debug("[SYNC] Closing source and target sessions");
            source.close();
            target.close();
        }
        logger.debug("[SYNC] Persisting offset {}", lastId);
        offsetStore.write(lastId);
        logger.info("Synchronization complete: scanned={}, copied={}, errors={}, offset={}, durationMillis={}",
                scanned, copied, errors, lastId, System.currentTimeMillis() - startedAt);
    }

    private List<RBTLogInfo> loadBatch(Session source, long offset, int batchSize, List<String> serverIpWhitelist) throws SQLException {
        List<RBTLogInfo> records = new ArrayList<>();
        logger.debug("[BATCH] Querying RBT_LOG: offset={}, maxResults={}, serverCount={}",
                offset, batchSize, serverIpWhitelist.size());
        SQLQuery query = source.createSQLQuery("SELECT ID, TONE_ID, TONE_CODE, ACTION_TYPE, FPATH, "
                + "TONE_NAME, SINGER, CP_CODE, ACTION_ACC, EXP_DATE, DESCRIPTION FROM RBT_LOG "
                + "WHERE ID > :offset AND RESULT = 1 AND ACTION_TYPE IN (1, 3, 15) "
                + "AND SERVER IN (:serverIpWhitelist) ORDER BY ID ASC");
        query.setLong("offset", offset);
        query.setParameterList("serverIpWhitelist", serverIpWhitelist);
        query.setMaxResults(batchSize);
        List<?> rows = query.list();
        for (Object value : rows) {
            Object[] row = (Object[]) value;
            records.add(new RBTLogInfo(((Number) row[0]).longValue(), row[1].toString(),
                    row[2] == null ? null : row[2].toString(), ((Number) row[3]).intValue(),
                    stringValue(row[4]), stringValue(row[5]), stringValue(row[6]), stringValue(row[7]),
                    stringValue(row[8]), row[9] == null ? null : (Timestamp) row[9], stringValue(row[10])));
        }
        if (records.isEmpty()) {
            logger.debug("[BATCH] No eligible RBT_LOG records found after offset {}", offset);
        } else {
            logger.debug("[BATCH] Loaded {} record(s), ID range {}-{}", records.size(),
                    records.get(0).getId(), records.get(records.size() - 1).getId());
        }
        return records;
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private int process(Session target, Session source, RBTLogInfo record, SchemaCopyPlan copyPlan) throws SQLException {
        if (record.getToneCode() == null || record.getToneCode().trim().isEmpty()) {
            logger.debug("[RECORD] Validation failed: logId={} has empty TONE_CODE", record.getId());
            throw new SQLException("TONE_CODE is empty");
        }
        if (record.getActionType() == 15) {
            logger.debug("[RECORD] Action 15 selected: deleting toneCode={}, toneId={} from {} table(s)",
                    record.getToneCode(), record.getToneId(), ACTION_15_DELETE_TABLES.length);
            insertRbtDelAll(target, record);
            int deleted = 0;
            for (String table : ACTION_15_DELETE_TABLES) {
                if (TONE_CATEGORY.equals(table)) {
                    logger.debug("[RECORD] Deleting TONE_CATEGORY by toneId={}", record.getToneId());
                    deleted += copier.deleteByToneId(target, table, record.getToneId());
                } else {
                    deleted += copier.deleteByToneCode(target, table, record.getToneCode());
                }
            }
            deletedFileWav(record.getFilePath());
            deletedFileMp3(record.getFilePath());
            deletedFileAmr(record.getFilePath());
            logger.debug("[RECORD] Action 15 completed: logId={}, deletedRows={}", record.getId(), deleted);
            return deleted;
        }

        logger.debug("[RECORD] Synchronizing MAP_CP_RBT: logId={}, toneCode={}", record.getId(), record.getToneCode());
        int synchronizedRows = copier.synchronizeLatestByToneCode(source, target, copyPlan.getMapCpRbt(), record.getToneCode()) ? 1 : 0;
        if (record.getActionType() == 1) {
            logger.debug("[RECORD] Synchronizing TONELIST: logId={}, toneCode={}", record.getId(), record.getToneCode());
            if (copier.synchronizeLatestByToneCode(source, target, copyPlan.getTonelist(), record.getToneCode())) {
                synchronizedRows++;
            }
        }
        return synchronizedRows;
    }

    private void insertRbtDelAll(Session target, RBTLogInfo record) throws SQLException {
        String sql = "INSERT INTO RBT_DEL_ALL (TONE_ID, TONE_CODE, TONE_NAME, SINGER, CP_CODE, FPATH, "
                + "AUTHORNAME, ACTION_ACC, STATE_DEL_SITE_2, STATE_DEL_SITE_3, CREATE_DATE, EXP_DATE, DESCRIPTION) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?)";
        logger.debug("[RBT_DEL_ALL] Inserting delete request: logId={}, toneId={}, toneCode={}",
                record.getId(), record.getToneId(), record.getToneCode());
        try (PreparedStatement statement = target.connection().prepareStatement(sql)) {
            statement.setString(1, record.getToneId());
            statement.setString(2, record.getToneCode());
            statement.setString(3, record.getToneName());
            statement.setString(4, record.getSinger());
            statement.setString(5, record.getCpCode());
            statement.setString(6, record.getFilePath());
            statement.setString(7, null);
            statement.setString(8, record.getActionAccount());
            statement.setTimestamp(9, new Timestamp(System.currentTimeMillis()));
            statement.setTimestamp(10, record.getExpirationDate());
            statement.setString(11, record.getDescription());
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Expected one inserted row in RBT_DEL_ALL");
            }
        }
        logger.debug("[RBT_DEL_ALL] Delete request inserted: logId={}, toneId={}, toneCode={}",
                record.getId(), record.getToneId(), record.getToneCode());
    }

    private void deletedFileWav(String path) {
        String wavPath = replaceExtension(path, ".amr", ".wav");
        deleteFile(tempDirectory, wavPath, "TEMP");
        deleteFile(wavDirectory, wavPath, "WAV");
    }

    private void deletedFileMp3(String path) {
        String mp3Path = replaceExtension(replaceExtension(path, ".wav", ".mp3"), ".amr", ".mp3");
        deleteFile(tempMusicDirectory, mp3Path, "TEMP_MUSIC");
    }

    private void deletedFileAmr(String path) {
        String amrPath = replaceExtension(path, ".wav", ".amr");
        deleteFile(amrDirectory, amrPath, "AMR");
    }

    private String replaceExtension(String path, String sourceExtension, String targetExtension) {
        if (path != null && path.length() >= sourceExtension.length()
                && path.regionMatches(true, path.length() - sourceExtension.length(), sourceExtension, 0,
                sourceExtension.length())) {
            return path.substring(0, path.length() - sourceExtension.length()) + targetExtension;
        }
        return path;
    }

    private void deleteFile(Path rootDirectory, String relativePath, String directoryName) {
        if (relativePath == null || relativePath.trim().isEmpty()) {
            logger.warn("[FILE_DELETE] Skipped {} because RBT_LOG.FPATH is empty", directoryName);
            return;
        }
        try {
            String normalizedRelativePath = relativePath.replace('\\', '/');
            while (normalizedRelativePath.startsWith("/")) {
                normalizedRelativePath = normalizedRelativePath.substring(1);
            }
            Path file = rootDirectory.resolve(Paths.get(normalizedRelativePath)).normalize();
            if (!file.startsWith(rootDirectory)) {
                logger.error("[FILE_DELETE] Rejected path outside {}: {}", directoryName, relativePath);
                return;
            }
            if (Files.deleteIfExists(file)) {
                logger.debug("[FILE_DELETE] Deleted {} file: {}", directoryName, file);
            } else {
                logger.debug("[FILE_DELETE] File does not exist in {}: {}", directoryName, file);
            }
        } catch (IOException | RuntimeException exception) {
            logger.error("[FILE_DELETE] Unable to delete {} file for FPATH {}: {}",
                    directoryName, relativePath, exception.getMessage(), exception);
        }
    }

    private void writeSyncLog(SessionFactory targetFactory, RBTLogInfo record, String description, int state) {
        Session logSession = null;
        try {
            logger.debug("[SYNC_LOG] Writing TONELIST_SYNLOG: logId={}, state={}", record.getId(), state);
            logSession = targetFactory.openSession();
            insertSyncLog(logSession, record, description, state);
            logSession.connection().commit();
            logger.debug("[SYNC_LOG] TONELIST_SYNLOG committed: logId={}, state={}", record.getId(), state);
        } catch (Exception exception) {
            if (logSession != null) {
                try {
                    rollback(logSession.connection(), exception);
                } catch (Exception rollbackException) {
                    exception.addSuppressed(rollbackException);
                }
            }
            logger.error("Unable to write TONELIST_SYNLOG for RBT_LOG ID {}: {}", record.getId(), exception.getMessage(), exception);
        } finally {
            if (logSession != null) {
                try {
                    logSession.close();
                } catch (Exception closeException) {
                    logger.error("Unable to close TONELIST_SYNLOG session for RBT_LOG ID {}: {}", record.getId(), closeException.getMessage(), closeException);
                }
            }
        }
    }

    private void insertSyncLog(Session logSession, RBTLogInfo record, String description, int state) throws SQLException {
        String sql = "INSERT INTO TONELIST_SYNLOG (TONE_ID, TONE_CODE, MOD_DATE, DESCRIPTION, STATE) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = logSession.connection().prepareStatement(sql)) {
            statement.setString(1, record.getToneId());
            statement.setString(2, record.getToneCode());
            statement.setTimestamp(3, new Timestamp(System.currentTimeMillis()));
            statement.setString(4, description);
            statement.setInt(5, state);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Expected one inserted row in TONELIST_SYNLOG");
            }
        }
    }

    private void rollback(Connection connection, Exception originalException) {
        try {
            connection.rollback();
            logger.debug("[TRANSACTION] Rollback completed");
        } catch (SQLException rollbackException) {
            originalException.addSuppressed(rollbackException);
        }
    }

}
