package org.example.sync;

import org.hibernate.Session;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JDBC-only copier, deliberately compatible with Hibernate 3.1.3.
 */
final class DatabaseRowCopier {
    boolean exists(Session session, String table, String toneCode) throws SQLException {
        Connection connection = session.connection();
        String sql = "SELECT 1 FROM " + quotedTable(connection, table) + " WHERE "
                + quoted(connection, "TONE_CODE") + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, toneCode);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    /**
     * Copies precisely one row identified by TONE_CODE. Returns false when no source row exists.
     */
    boolean copyByToneCode(Session source, Session destination, String table, String toneCode) throws SQLException {
        Connection sourceConnection = source.connection();
        Connection destinationConnection = destination.connection();
        String select = "SELECT * FROM " + quotedTable(sourceConnection, table) + " WHERE "
                + quoted(sourceConnection, "TONE_CODE") + " = ?";
        try (PreparedStatement selectStatement = sourceConnection.prepareStatement(select)) {
            selectStatement.setString(1, toneCode);
            try (ResultSet sourceRow = selectStatement.executeQuery()) {
                if (!sourceRow.next()) {
                    return false;
                }
                ResultSetMetaData sourceMetadata = sourceRow.getMetaData();
                Set<String> destinationColumns = columns(destinationConnection, table);
                List<String> copyColumns = new ArrayList<String>();
                for (int index = 1; index <= sourceMetadata.getColumnCount(); index++) {
                    String column = sourceMetadata.getColumnLabel(index);
                    if (destinationColumns.contains(column.toUpperCase())) {
                        copyColumns.add(column);
                    }
                }
                if (copyColumns.isEmpty()) {
                    throw new SQLException("No common columns available for " + table);
                }
                StringBuilder insert = new StringBuilder("INSERT INTO ").append(quotedTable(destinationConnection, table)).append(" (");
                StringBuilder values = new StringBuilder(" VALUES (");
                for (int index = 0; index < copyColumns.size(); index++) {
                    if (index > 0) {
                        insert.append(", ");
                        values.append(", ");
                    }
                    insert.append(quoted(destinationConnection, copyColumns.get(index)));
                    values.append('?');
                }
                insert.append(')').append(values).append(')');
                try (PreparedStatement insertStatement = destinationConnection.prepareStatement(insert.toString())) {
                    for (int index = 0; index < copyColumns.size(); index++) {
                        insertStatement.setObject(index + 1, sourceRow.getObject(copyColumns.get(index)));
                    }
                    if (insertStatement.executeUpdate() != 1) {
                        throw new SQLException("Expected one inserted row in " + table);
                    }
                }
                return true;
            }
        }
    }

    private Set<String> columns(Connection connection, String table) throws SQLException {
        Set<String> columns = new HashSet<String>();
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet result = metadata.getColumns(connection.getCatalog(), null, table, null)) {
            while (result.next()) {
                columns.add(result.getString("COLUMN_NAME").toUpperCase());
            }
        }
        if (columns.isEmpty()) {
            try (ResultSet result = metadata.getColumns(null, null, table, null)) {
                while (result.next()) {
                    columns.add(result.getString("COLUMN_NAME").toUpperCase());
                }
            }
        }
        return columns;
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
