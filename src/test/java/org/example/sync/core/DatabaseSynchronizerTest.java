package org.example.sync.core;

import org.example.sync.copy.SchemaCopyPlan;
import org.example.sync.scheduler.DatabaseSynchronizer;
import org.example.sync.scheduler.OffsetStore;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DatabaseSynchronizerTest {
    private static final String[] DELETE_TABLES = {
            "SPECIAL_TONELIST", "TOP_HOT", "TOP_MONTH", "TOP_WEEK", "TONE_TOPIC",
            "RBT_SYNTAX_REPRESENT", "TONE_SMS_SYNTAX", "INTRO_RBT_CONFIG",
            "MAP_CATEGORY_RBT_HOT", "CORP_RBT", "CORP_MSISDN_ACTION"
    };

    private SessionFactory targetFactory;
    private SessionFactory sourceFactory;
    private Session target;
    private Session source;

    @Before
    public void setUp() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        targetFactory = factory("sync_target_" + suffix);
        sourceFactory = factory("sync_source_" + suffix);
        target = targetFactory.openSession();
        source = sourceFactory.openSession();
        createSchema(target);
        createSchema(source);
    }

    @After
    public void tearDown() {
        if (target != null) { target.close(); }
        if (source != null) { source.close(); }
        if (targetFactory != null) { targetFactory.close(); }
        if (sourceFactory != null) { sourceFactory.close(); }
    }

    @Test
    public void action3InsertsMissingUpdatesOlderAndSkipsNewerMapRows() throws Exception {
        insertBusinessRow(source, "MAP_CP_RBT", 1, "NEW", "source", "2026-01-02 00:00:00");
        insertBusinessRow(source, "MAP_CP_RBT", 2, "OLDER_TARGET", "source", "2026-01-02 00:00:00");
        insertBusinessRow(target, "MAP_CP_RBT", 20, "OLDER_TARGET", "target-old", "2026-01-01 00:00:00");
        insertBusinessRow(source, "MAP_CP_RBT", 3, "NEWER_TARGET", "source-old", "2026-01-01 00:00:00");
        insertBusinessRow(target, "MAP_CP_RBT", 30, "NEWER_TARGET", "target-new", "2026-01-03 00:00:00");
        insertLog(1, "NEW", 3);
        insertLog(2, "OLDER_TARGET", 3);
        insertLog(3, "NEWER_TARGET", 3);
        commitSetupData();

        synchronize();

        assertEquals("source", value(target, "MAP_CP_RBT", "NEW"));
        assertEquals("source", value(target, "MAP_CP_RBT", "OLDER_TARGET"));
        assertEquals("target-new", value(target, "MAP_CP_RBT", "NEWER_TARGET"));
        assertEquals(3, scalarInt(target, "SELECT COUNT(*) FROM TONELIST_SYNLOG WHERE STATE = 1"));
        assertEquals(0, scalarInt(target, "SELECT COUNT(*) FROM TONELIST"));
    }

    @Test
    public void action1SynchronizesMapThenToneListAndRollsBackBothOnError() throws Exception {
        insertBusinessRow(source, "MAP_CP_RBT", 1, "OK", "new-map", "2026-01-02 00:00:00");
        insertBusinessRow(source, "TONELIST", 1, "OK", "new-tone", "2026-01-02 00:00:00");
        insertBusinessRow(target, "MAP_CP_RBT", 10, "OK", "old-map", "2026-01-01 00:00:00");
        insertBusinessRow(target, "TONELIST", 10, "OK", "old-tone", "2026-01-01 00:00:00");

        insertBusinessRow(source, "MAP_CP_RBT", 2, "ROLLBACK", "changed-map", "2026-01-02 00:00:00");
        insertBusinessRow(target, "MAP_CP_RBT", 20, "ROLLBACK", "original-map", "2026-01-01 00:00:00");
        insertLog(1, "OK", 1);
        insertLog(2, "ROLLBACK", 1);
        commitSetupData();

        synchronize();

        assertEquals("new-map", value(target, "MAP_CP_RBT", "OK"));
        assertEquals("new-tone", value(target, "TONELIST", "OK"));
        assertEquals("original-map", value(target, "MAP_CP_RBT", "ROLLBACK"));
        assertEquals(1, scalarInt(target, "SELECT COUNT(*) FROM TONELIST_SYNLOG WHERE STATE = 1"));
        assertEquals(1, scalarInt(target, "SELECT COUNT(*) FROM TONELIST_SYNLOG WHERE STATE = 0"));
    }

    @Test
    public void missingSourceAndEmptyToneCodeAreLoggedAsErrors() throws Exception {
        insertLog(1, "MISSING", 3);
        execute(source, "INSERT INTO RBT_LOG VALUES (2, 1002, NULL, 3, 1, '10.0.0.1')");
        commitSetupData();

        synchronize();

        assertEquals(2, scalarInt(target, "SELECT COUNT(*) FROM TONELIST_SYNLOG WHERE STATE = 0"));
        assertTrue(scalarString(target,
                "SELECT DESCRIPTION FROM TONELIST_SYNLOG WHERE TONE_ID = 1001").contains("MAP_CP_RBT"));
        assertEquals("TONE_CODE is empty", scalarString(target,
                "SELECT DESCRIPTION FROM TONELIST_SYNLOG WHERE TONE_ID = 1002"));
    }

    @Test
    public void logWriteFailureDoesNotStopSynchronizationOrOffsetUpdate() throws Exception {
        insertBusinessRow(source, "MAP_CP_RBT", 1, "FIRST", "first", "2026-01-02 00:00:00");
        insertBusinessRow(source, "MAP_CP_RBT", 2, "SECOND", "second", "2026-01-02 00:00:00");
        insertLog(1, "FIRST", 3);
        insertLog(2, "SECOND", 3);
        commitSetupData();
        execute(target, "DROP TABLE TONELIST_SYNLOG");
        target.connection().commit();

        Path offset = Files.createTempDirectory("sync-log-failure-").resolve("offset.txt");
        new DatabaseSynchronizer().synchronize(targetFactory, sourceFactory, 100,
                new OffsetStore(offset), Arrays.asList("10.0.0.1"),
                SchemaCopyPlan.loadFromSource(sourceFactory));

        assertEquals("first", value(target, "MAP_CP_RBT", "FIRST"));
        assertEquals("second", value(target, "MAP_CP_RBT", "SECOND"));
        assertEquals("2", new String(Files.readAllBytes(offset)).trim());
    }

    @Test
    public void action15DeletesToneCodeFromAllRequiredTables() throws Exception {
        insertBusinessRow(target, "MAP_CP_RBT", 1, "DELETE_ME", "map", "2026-01-01 00:00:00");
        insertBusinessRow(target, "TONELIST", 1, "DELETE_ME", "tone", "2026-01-01 00:00:00");
        for (String table : DELETE_TABLES) {
            execute(target, "INSERT INTO " + table + " VALUES ('DELETE_ME')");
            execute(target, "INSERT INTO " + table + " VALUES ('KEEP')");
        }
        insertLog(1, "DELETE_ME", 15);
        commitSetupData();

        synchronize();

        assertFalse(exists(target, "MAP_CP_RBT", "DELETE_ME"));
        assertFalse(exists(target, "TONELIST", "DELETE_ME"));
        for (String table : DELETE_TABLES) {
            assertFalse(exists(target, table, "DELETE_ME"));
            assertTrue(exists(target, table, "KEEP"));
        }
        assertEquals("success", scalarString(target, "SELECT DESCRIPTION FROM TONELIST_SYNLOG"));
        assertEquals(1, scalarInt(target, "SELECT STATE FROM TONELIST_SYNLOG"));
    }

    private void synchronize() throws Exception {
        Path offset = Files.createTempDirectory("sync-result-").resolve("offset.txt");
        new DatabaseSynchronizer().synchronize(targetFactory, sourceFactory, 100,
                new OffsetStore(offset), Arrays.asList("10.0.0.1"),
                SchemaCopyPlan.loadFromSource(sourceFactory));
    }

    private void insertLog(long id, String toneCode, int actionType) throws Exception {
        execute(source, "INSERT INTO RBT_LOG VALUES (" + id + ", " + (1000 + id) + ", '"
                + toneCode + "', " + actionType + ", 1, '10.0.0.1')");
    }

    private void insertBusinessRow(Session session, String table, long id, String toneCode,
                                   String description, String modDate) throws Exception {
        execute(session, "INSERT INTO " + table + " VALUES (" + id + ", '" + toneCode + "', '"
                + description + "', TIMESTAMP '" + modDate + "')");
    }

    private SessionFactory factory(String name) {
        Configuration configuration = new Configuration();
        configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
        configuration.setProperty("hibernate.connection.url",
                "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        configuration.setProperty("hibernate.connection.username", "sa");
        configuration.setProperty("hibernate.connection.password", "");
        configuration.setProperty("hibernate.dialect", "org.hibernate.dialect.MySQLDialect");
        return configuration.buildSessionFactory();
    }

    private void createSchema(Session session) throws Exception {
        execute(session, "CREATE TABLE RBT_LOG (ID BIGINT PRIMARY KEY, TONE_ID BIGINT, "
                + "TONE_CODE VARCHAR(50), ACTION_TYPE INT, RESULT INT, SERVER VARCHAR(50))");
        execute(session, "CREATE TABLE MAP_CP_RBT (ID BIGINT PRIMARY KEY, TONE_CODE VARCHAR(50) UNIQUE, "
                + "DESCRIPTION VARCHAR(100), MOD_DATE TIMESTAMP)");
        execute(session, "CREATE TABLE TONELIST (ID BIGINT PRIMARY KEY, TONE_CODE VARCHAR(50) UNIQUE, "
                + "DESCRIPTION VARCHAR(100), MOD_DATE TIMESTAMP)");
        for (String table : DELETE_TABLES) {
            execute(session, "CREATE TABLE " + table + " (TONE_CODE VARCHAR(50) PRIMARY KEY)");
        }
        execute(session, "CREATE TABLE TONELIST_SYNLOG (LOG_ID BIGINT AUTO_INCREMENT PRIMARY KEY, "
                + "TONE_ID BIGINT, TONE_CODE VARCHAR(50), MOD_DATE TIMESTAMP, "
                + "DESCRIPTION VARCHAR(1000), STATE INT)");
    }

    private void execute(Session session, String sql) throws Exception {
        try (Statement statement = session.connection().createStatement()) {
            statement.execute(sql);
        }
    }

    private void commitSetupData() throws Exception {
        source.connection().commit();
        target.connection().commit();
    }

    private boolean exists(Session session, String table, String toneCode) throws Exception {
        try (Statement statement = session.connection().createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT 1 FROM " + table + " WHERE TONE_CODE = '" + toneCode + "'")) {
            return result.next();
        }
    }

    private String value(Session session, String table, String toneCode) throws Exception {
        return scalarString(session,
                "SELECT DESCRIPTION FROM " + table + " WHERE TONE_CODE = '" + toneCode + "'");
    }

    private int scalarInt(Session session, String sql) throws Exception {
        try (Statement statement = session.connection().createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    private String scalarString(Session session, String sql) throws Exception {
        try (Statement statement = session.connection().createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }
}
