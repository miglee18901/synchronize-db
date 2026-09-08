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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public final class DatabaseSynchronizer {
    private static final Logger logger = LogManager.getLogger(DatabaseSynchronizer.class);
    private final DatabaseRowCopier copier = new DatabaseRowCopier();

    public void synchronize(SessionFactory target16M, SessionFactory source21M, int batchSize, OffsetStore offsetStore,
                            List<String> serverIpWhitelist, SchemaCopyPlan copyPlan) throws IOException {
        long lastId = offsetStore.read();
        int scanned = 0;
        int copied = 0;
        int errors = 0;
        Session target = target16M.openSession();
        Session source = source21M.openSession();
        try {
            while (true) {
                List<RBTLogInfo> batch;
                try {
                    batch = loadBatch(source, lastId, batchSize, serverIpWhitelist);
                } catch (SQLException exception) {
                    throw new IOException("Unable to read RBT_LOG after ID " + lastId, exception);
                }
                if (batch.isEmpty()) {
                    break;
                }
                for (RBTLogInfo record : batch) {
                    scanned++;
                    lastId = record.getId();
                    try {
                        int recordCopied = process(target, source, record, copyPlan);
                        insertSyncLog(target, record, "success", 1);
                        target.connection().commit();
                        copied += recordCopied;
                    } catch (Exception exception) {
                        errors++;
                        rollback(target.connection(), exception);
                        logger.error("Synchronization failed: {}", exception.getMessage(), exception);
                        try {
                            insertSyncLog(target, record, exception.getMessage(), 0);
                            target.connection().commit();
                        } catch (SQLException logException) {
                            rollback(target.connection(), logException);
                            throw new IOException("Unable to insert failure into TONELIST_SYNLOG for RBT_LOG ID " + record.getId(), logException);
                        }
                    }
                }
            }
        } finally {
            source.close();
            target.close();
        }
        offsetStore.write(lastId);
        logger.info("Synchronization complete: scanned={}, copied={}, errors={}, offset={}", scanned, copied, errors, lastId);
    }

    private List<RBTLogInfo> loadBatch(Session source, long offset, int batchSize, List<String> serverIpWhitelist) throws SQLException {
        List<RBTLogInfo> records = new ArrayList<>();
        SQLQuery query = source.createSQLQuery("SELECT ID, TONE_ID, TONE_CODE, ACTION_TYPE, SERVER FROM RBT_LOG "
                + "WHERE ID > :offset AND RESULT = 1 AND ACTION_TYPE IN (1, 3) "
                + "AND SERVER IN (:serverIpWhitelist) ORDER BY ID ASC");
        query.setLong("offset", offset);
        query.setParameterList("serverIpWhitelist", serverIpWhitelist);
        query.setMaxResults(batchSize);
        List<?> rows = query.list();
        for (Object value : rows) {
            Object[] row = (Object[]) value;
            records.add(new RBTLogInfo(((Number) row[0]).longValue(), row[1].toString(), row[2] == null ? null : row[2].toString(),
                    ((Number) row[3]).intValue(), row[4] == null ? null : row[4].toString()));
        }
        return records;
    }

    private int process(Session target, Session source, RBTLogInfo record, SchemaCopyPlan copyPlan) throws SQLException {
        if (record.getToneCode() == null || record.getToneCode().trim().isEmpty()) {
            throw new SQLException("TONE_CODE is empty");
        }
        if (copier.exists(target, SchemaCopyPlan.MAP_CP_RBT, record.getToneCode())) {
            return 0;
        }
        if (!copier.exists(source, SchemaCopyPlan.MAP_CP_RBT, record.getToneCode())) {
            throw new SQLException("TONE_CODE does not exist in CRBT21M." + SchemaCopyPlan.MAP_CP_RBT);
        }

        if (!copier.copyByToneCode(source, target, copyPlan.getMapCpRbt(), record.getToneCode())) {
            throw new SQLException("MAP_CP_RBT source row disappeared before insert");
        }
        int copied = 1;
        if (record.getActionType() == 1 && !copier.exists(target, SchemaCopyPlan.TONELIST, record.getToneCode())) {
            if (!copier.exists(source, SchemaCopyPlan.TONELIST, record.getToneCode())) {
                throw new SQLException("TONE_CODE does not exist in CRBT21M." + SchemaCopyPlan.TONELIST);
            }
            if (!copier.copyByToneCode(source, target, copyPlan.getTonelist(), record.getToneCode())) {
                throw new SQLException("TONELIST source row disappeared before insert");
            }
            copied++;
        }
        return copied;
    }

    private void insertSyncLog(Session target, RBTLogInfo record, String description, int state) throws SQLException {
        String sql = "INSERT INTO TONELIST_SYNLOG (TONE_ID, TONE_CODE, MOD_DATE, DESCRIPTION, STATE) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = target.connection().prepareStatement(sql)) {
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
        } catch (SQLException rollbackException) {
            originalException.addSuppressed(rollbackException);
        }
    }

}
