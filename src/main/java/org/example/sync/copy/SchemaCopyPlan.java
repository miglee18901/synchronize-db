package org.example.sync.copy;

import org.hibernate.Session;
import org.hibernate.SessionFactory;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public final class SchemaCopyPlan {
    public static final String MAP_CP_RBT = "MAP_CP_RBT";
    public static final String TONELIST = "TONELIST";

    private final TableCopyPlan mapCpRbt;
    private final TableCopyPlan tonelist;

    private SchemaCopyPlan(TableCopyPlan mapCpRbt, TableCopyPlan tonelist) {
        this.mapCpRbt = mapCpRbt;
        this.tonelist = tonelist;
    }

    public static SchemaCopyPlan loadFromSource(SessionFactory sourceFactory) throws SQLException {
        Session source = sourceFactory.openSession();
        try {
            return new SchemaCopyPlan(loadTable(source.connection(), MAP_CP_RBT),
                    loadTable(source.connection(), TONELIST));
        } finally {
            source.close();
        }
    }

    public TableCopyPlan getMapCpRbt() {
        return mapCpRbt;
    }

    public TableCopyPlan getTonelist() {
        return tonelist;
    }

    private static TableCopyPlan loadTable(Connection source, String table) throws SQLException {
        Map<String, String> sourceColumns = columns(source, table);
        if (sourceColumns.isEmpty()) {
            throw new SQLException("Unable to read columns for " + table);
        }
        return new TableCopyPlan(table, new ArrayList<>(sourceColumns.values()));
    }

    private static Map<String, String> columns(Connection connection, String table) throws SQLException {
        Map<String, String> columns = new TreeMap<>();
        DatabaseMetaData metadata = connection.getMetaData();
        collectColumns(metadata.getColumns(connection.getCatalog(), null, table, null), columns);
        if (columns.isEmpty()) {
            collectColumns(metadata.getColumns(null, null, table, null), columns);
        }
        return columns;
    }

    private static void collectColumns(ResultSet result, Map<String, String> columns) throws SQLException {
        try {
            while (result.next()) {
                String name = result.getString("COLUMN_NAME");
                columns.put(name.toUpperCase(), name);
            }
        } finally {
            result.close();
        }
    }

    public static final class TableCopyPlan {
        private final String table;
        private final List<String> columns;

        private TableCopyPlan(String table, List<String> columns) {
            this.table = table;
            this.columns = columns;
        }

        public String getTable() {
            return table;
        }

        public List<String> getColumns() {
            return columns;
        }
    }
}
