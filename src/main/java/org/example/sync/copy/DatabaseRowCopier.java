package org.example.sync.copy;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.hibernate.Session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

public final class DatabaseRowCopier {
    private static final Logger logger = LogManager.getLogger(DatabaseRowCopier.class);

    public boolean synchronizeLatestByToneCode(Session source, Session destination, SchemaCopyPlan.TableCopyPlan plan, String toneCode) throws SQLException {
        Connection sourceConnection = source.connection();
        Connection destinationConnection = destination.connection();
        String table = plan.getTable();
        logger.debug("[TABLE_SYNC] Reading source row: table={}, toneCode={}", table, toneCode);
        String select = "SELECT * FROM " + quotedTable(sourceConnection, table) + " WHERE " + quoted(sourceConnection, "TONE_CODE") + " = ?";
        try (PreparedStatement sourceStatement = sourceConnection.prepareStatement(select)) {
            sourceStatement.setString(1, toneCode);
            try (ResultSet sourceRow = sourceStatement.executeQuery()) {
                if (!sourceRow.next()) {
                    throw new SQLException("TONE_CODE does not exist in CRBT21M." + table);
                }
                Timestamp sourceModDate = sourceRow.getTimestamp("MOD_DATE");
                Timestamp destinationModDate = findModDate(destinationConnection, table, toneCode);
                logger.debug("[TABLE_SYNC] Comparing rows: table={}, toneCode={}, sourceModDate={}, destinationModDate={}",
                        table, toneCode, sourceModDate, destinationModDate);
                if (destinationModDate == null && !existsByToneCode(destinationConnection, table, toneCode)) {
                    logger.debug("[TABLE_SYNC] Destination row missing; inserting: table={}, toneCode={}", table, toneCode);
                    insertRow(destinationConnection, plan, sourceRow);
                    destinationConnection.commit();
                    logger.debug("[TABLE_SYNC] Insert completed: table={}, toneCode={}", table, toneCode);
                    return true;
                }
                if (sourceModDate != null && destinationModDate != null && !destinationModDate.before(sourceModDate)) {
                    throw new SQLException("CRBT16M." + table + " has MOD_DATE newer than or equal to CRBT21M: "
                            + "toneCode=" + toneCode
                            + ", crbt16mModDate=" + destinationModDate
                            + ", crbt21mModDate=" + sourceModDate);
                }
                if (sourceModDate != null) {
                    String sourceToneId = sourceRow.getString("TONE_ID");
                    String destinationToneId = findToneId(destinationConnection, table, toneCode);
                    if (!Objects.equals(sourceToneId, destinationToneId)) {
                        throw new SQLException("TONE_ID mismatch in " + table + " for TONE_CODE " + toneCode + ": source=" + sourceToneId + ", destination=" + destinationToneId);
                    }
                    logger.debug("[TABLE_SYNC] Source row is newer; updating: table={}, toneCode={}", table, toneCode);
                    updateRow(destinationConnection, plan, sourceRow, toneCode);
                    destinationConnection.commit();
                    logger.debug("[TABLE_SYNC] Update completed: table={}, toneCode={}", table, toneCode);
                    return true;
                }
                logger.debug("[TABLE_SYNC] Destination row is current; skipping: table={}, toneCode={}", table, toneCode);
                return false;
            }
        }
    }

    public int deleteByToneCode(Session destination, String table, String toneCode) throws SQLException {
        return deleteByColumn(destination, table, "TONE_CODE", toneCode);
    }

    public int deleteByToneId(Session destination, String table, String toneId) throws SQLException {
        return deleteByColumn(destination, table, "TONE_ID", toneId);
    }

    private int deleteByColumn(Session destination, String table, String column, String value) throws SQLException {
        Connection connection = destination.connection();
        String sql = "DELETE FROM " + quotedTable(connection, table) + " WHERE " + quoted(connection, column) + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, value);
            int deleted = statement.executeUpdate();
            connection.commit();
            logger.debug("[TABLE_DELETE] Completed: table={}, keyColumn={}, keyValue={}, deletedRows={}",
                    table, column, value, deleted);
            return deleted;
        }
    }

    private Timestamp findModDate(Connection connection, String table, String toneCode) throws SQLException {
        String sql = "SELECT " + quoted(connection, "MOD_DATE") + " FROM " + quotedTable(connection, table) + " WHERE " + quoted(connection, "TONE_CODE") + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, toneCode);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getTimestamp(1) : null;
            }
        }
    }

    private String findToneId(Connection connection, String table, String toneCode) throws SQLException {
        String sql = "SELECT " + quoted(connection, "TONE_ID") + " FROM " + quotedTable(connection, table)
                + " WHERE " + quoted(connection, "TONE_CODE") + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, toneCode);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private boolean existsByToneCode(Connection connection, String table, String toneCode) throws SQLException {
        String sql = "SELECT 1 FROM " + quotedTable(connection, table) + " WHERE " + quoted(connection, "TONE_CODE") + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, toneCode);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    private void insertRow(Connection destination, SchemaCopyPlan.TableCopyPlan plan, ResultSet sourceRow) throws SQLException {
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(quotedTable(destination, plan.getTable())).append(" (");
        StringBuilder values = new StringBuilder(" VALUES (");
        List<String> columns = plan.getColumns();
        for (int index = 0; index < columns.size(); index++) {
            if (index > 0) {
                sql.append(", ");
                values.append(", ");
            }
            sql.append(quoted(destination, columns.get(index)));
            values.append('?');
        }
        sql.append(')').append(values).append(')');
        try (PreparedStatement statement = destination.prepareStatement(sql.toString())) {
            bindColumns(statement, sourceRow, columns, false);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Expected one inserted row in " + plan.getTable());
            }
        }
    }

    private void updateRow(Connection destination, SchemaCopyPlan.TableCopyPlan plan, ResultSet sourceRow, String toneCode) throws SQLException {
        StringBuilder sql = new StringBuilder("UPDATE ").append(quotedTable(destination, plan.getTable())).append(" SET ");
        int parameterCount = 0;
        for (String column : plan.getColumns()) {
            if ("TONE_CODE".equalsIgnoreCase(column)) {
                continue;
            }
            if (parameterCount++ > 0) {
                sql.append(", ");
            }
            sql.append(quoted(destination, column)).append(" = ?");
        }
        sql.append(" WHERE ").append(quoted(destination, "TONE_CODE")).append(" = ?");
        try (PreparedStatement statement = destination.prepareStatement(sql.toString())) {
            int nextIndex = bindColumns(statement, sourceRow, plan.getColumns(), true);
            statement.setString(nextIndex, toneCode);
            if (statement.executeUpdate() != 1) {
                throw new SQLException("Expected one updated row in " + plan.getTable());
            }
        }
    }

    private int bindColumns(PreparedStatement statement, ResultSet sourceRow, List<String> columns, boolean excludeToneCode) throws SQLException {
        int parameterIndex = 1;
        for (String column : columns) {
            if (!excludeToneCode || !"TONE_CODE".equalsIgnoreCase(column)) {
                statement.setObject(parameterIndex++, sourceRow.getObject(column));
            }
        }
        return parameterIndex;
    }

    private String quotedTable(Connection connection, String table) throws SQLException {
        return quoted(connection, table);
    }

    private String quoted(Connection connection, String identifier) throws SQLException {
        if (identifier == null || !identifier.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new IllegalArgumentException("Invalid SQL identifier: " + identifier);
        }
        String quote = connection.getMetaData().getIdentifierQuoteString();
        return quote == null || quote.trim().isEmpty() ? identifier : quote + identifier + quote;
    }
}
