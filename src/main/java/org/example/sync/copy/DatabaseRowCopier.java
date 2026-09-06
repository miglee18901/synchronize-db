package org.example.sync.copy;

import org.hibernate.Session;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

public final class DatabaseRowCopier {
    public boolean exists(Session session, String table, String toneCode) throws SQLException {
        Connection connection = session.connection();
        String sql = "SELECT 1 FROM " + quotedTable(connection, table) + " WHERE " + quoted(connection, "TONE_CODE") + " = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, toneCode);
            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    public boolean copyByToneCode(Session source, Session destination, SchemaCopyPlan.TableCopyPlan plan, String toneCode) throws SQLException {
        Connection sourceConnection = source.connection();
        Connection destinationConnection = destination.connection();
        String table = plan.getTable();
        List<String> copyColumns = plan.getColumns();
        String select = "SELECT * FROM " + quotedTable(sourceConnection, table) + " WHERE " + quoted(sourceConnection, "TONE_CODE") + " = ?";
        try (PreparedStatement selectStatement = sourceConnection.prepareStatement(select)) {
            selectStatement.setString(1, toneCode);
            try (ResultSet sourceRow = selectStatement.executeQuery()) {
                if (!sourceRow.next()) {
                    return false;
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
