package org.example.sync.copy;

import org.hibernate.Session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;

public final class DatabaseRowCopier {

    public boolean synchronizeLatestByToneCode(Session source, Session destination, SchemaCopyPlan.TableCopyPlan plan, String toneCode) throws SQLException {
        Connection sourceConnection = source.connection();
        Connection destinationConnection = destination.connection();
        String table = plan.getTable();
        String select = "SELECT * FROM " + quotedTable(sourceConnection, table) + " WHERE " + quoted(sourceConnection, "TONE_CODE") + " = ?";
        try (PreparedStatement sourceStatement = sourceConnection.prepareStatement(select)) {
            sourceStatement.setString(1, toneCode);
            try (ResultSet sourceRow = sourceStatement.executeQuery()) {
                if (!sourceRow.next()) {
                    throw new SQLException("TONE_CODE does not exist in CRBT21M." + table);
                }
                Timestamp sourceModDate = sourceRow.getTimestamp("MOD_DATE");
                Timestamp destinationModDate = findModDate(destinationConnection, table, toneCode);
                if (destinationModDate == null && !existsByToneCode(destinationConnection, table, toneCode)) {
                    insertRow(destinationConnection, plan, sourceRow);
                    return true;
                }
                if (sourceModDate != null && (destinationModDate == null || destinationModDate.before(sourceModDate))) {
                    updateRow(destinationConnection, plan, sourceRow, toneCode);
                    return true;
                }
                return false;
            }
        }
    }

    public int deleteByToneCode(Session destination, String table, String toneCode) throws SQLException {
        Connection connection = destination.connection();
        String sql = "DELETE FROM " + quotedTable(connection, table) + " WHERE " + quoted(connection, "TONE_CODE") + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, toneCode);
            return statement.executeUpdate();
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
