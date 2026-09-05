package org.example.sync;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.SQLQuery;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Synchronizes records selected from CRBT21M RBT_LOG into CRBT16M.
 */
public final class DatabaseSynchronizer {
    private static final Logger logger = LogManager.getLogger(DatabaseSynchronizer.class);
    private static final String MAP_CP_RBT = "MAP_CP_RBT";
    private static final String TONELIST = "TONELIST";
    private final DatabaseRowCopier copier = new DatabaseRowCopier();

    public SyncRunResult synchronize(SessionFactory target16M, SessionFactory source21M,
                                     int batchSize, OffsetStore offsetStore, File reportDirectory) throws IOException {
        long initialOffset = offsetStore.read();
        long lastId = initialOffset;
        int scanned = 0;
        int successful = 0;
        int copied = 0;
        int errors = 0;
        List<String> errorDetails = new ArrayList<String>();
        Session target = target16M.openSession();
        Session source = source21M.openSession();
        try {
            while (true) {
                List<SyncLogRecord> batch;
                try {
                    batch = loadBatch(source, lastId, batchSize);
                } catch (SQLException exception) {
                    throw new IOException("Unable to read RBT_LOG after ID " + lastId, exception);
                }
                if (batch.isEmpty()) {
                    break;
                }
                for (SyncLogRecord record : batch) {
                    scanned++;
                    lastId = record.getId();
                    try {
                        copied += process(target, source, record);
                        successful++;
                    } catch (Exception exception) {
                        errors++;
                        String message = "ID=" + record.getId() + ", TONE_CODE=" + printable(record.getToneCode())
                                + ", ACTION_TYPE=" + record.getActionType() + ", ERROR=" + exception.getMessage();
                        logger.error("Synchronization failed: {}", message, exception);
                        errorDetails.add(message);
                    }
                }
            }
        } finally {
            source.close();
            target.close();
        }
        if (!errorDetails.isEmpty()) {
            writeErrorReport(reportDirectory, errorDetails);
        }
        offsetStore.write(lastId);
        logger.info("Synchronization complete: scanned={}, copied={}, errors={}, offset={}", scanned, copied, errors, lastId);
        return new SyncRunResult(initialOffset, lastId, scanned, copied, errors);
    }

    /** Writes a report only when this run contains row-level synchronization errors. */
    private void writeErrorReport(File errorDirectory, List<String> errorDetails) throws IOException {
        Files.createDirectories(errorDirectory.toPath());
        File errorFile = new File(errorDirectory,
                "sync_error_" + new SimpleDateFormat("yyyyMMddHHmmss").format(new Date()) + ".txt");
        Files.write(errorFile.toPath(), errorDetails, StandardCharsets.UTF_8);
    }

    private List<SyncLogRecord> loadBatch(Session source, long offset, int batchSize) throws SQLException {
        List<SyncLogRecord> records = new ArrayList<SyncLogRecord>();
        // setMaxResults is rendered by Hibernate using the configured database dialect
        // (ROWNUM for Oracle, LIMIT for MySQL, etc.), instead of embedding MySQL LIMIT.
        SQLQuery query = source.createSQLQuery(
                "SELECT ID, TONE_CODE, ACTION_TYPE FROM RBT_LOG "
                        + "WHERE ID > :offset AND RESULT = 1 ORDER BY ID ASC");
        query.setLong("offset", offset);
        query.setMaxResults(batchSize);
        List rows = query.list();
        for (Object value : rows) {
            Object[] row = (Object[]) value;
            records.add(new SyncLogRecord(((Number) row[0]).longValue(),
                    row[1] == null ? null : row[1].toString(), ((Number) row[2]).intValue()));
        }
        return records;
    }

    private int process(Session target, Session source, SyncLogRecord record) throws SQLException {
        if (record.getActionType() != 1 && record.getActionType() != 3) {
            return 0;
        }
        if (record.getToneCode() == null || record.getToneCode().trim().isEmpty()) {
            throw new SQLException("TONE_CODE is empty");
        }
        if (copier.exists(target, MAP_CP_RBT, record.getToneCode())) {
            return 0;
        }
        if (!copier.exists(source, MAP_CP_RBT, record.getToneCode())) {
            throw new SQLException("TONE_CODE does not exist in CRBT21M." + MAP_CP_RBT);
        }

        Connection connection = target.connection();
        boolean oldAutoCommit = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            if (!copier.copyByToneCode(source, target, MAP_CP_RBT, record.getToneCode())) {
                throw new SQLException("MAP_CP_RBT source row disappeared before insert");
            }
            int copied = 1;
            if (record.getActionType() == 1 && !copier.exists(target, TONELIST, record.getToneCode())) {
                if (!copier.exists(source, TONELIST, record.getToneCode())) {
                    throw new SQLException("TONE_CODE does not exist in CRBT21M." + TONELIST);
                }
                if (!copier.copyByToneCode(source, target, TONELIST, record.getToneCode())) {
                    throw new SQLException("TONELIST source row disappeared before insert");
                }
                copied++;
            }
            connection.commit();
            return copied;
        } catch (SQLException exception) {
            connection.rollback();
            throw exception;
        } finally {
            connection.setAutoCommit(oldAutoCommit);
        }
    }

    private String printable(String value) {
        return value == null ? "<null>" : "'" + value + "'";
    }
}
