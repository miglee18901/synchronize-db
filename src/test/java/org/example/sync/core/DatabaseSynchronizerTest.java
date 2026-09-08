package org.example.sync.core;

import org.example.sync.scheduler.DatabaseSynchronizer;
import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.example.sync.copy.SchemaCopyPlan;
import org.example.sync.scheduler.OffsetStore;
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
    public void copiesMapForAction3AndMapPlusToneListForAction1() throws Exception {
        execute(source, "INSERT INTO MAP_CP_RBT VALUES (101, 'TONE_A', 'map A')");
        execute(source, "INSERT INTO TONELIST VALUES (201, 'TONE_A', 'tone A')");
        execute(source, "INSERT INTO MAP_CP_RBT VALUES (102, 'TONE_B', 'map B')");
        execute(source, "INSERT INTO TONELIST VALUES (202, 'TONE_B', 'tone B')");
        execute(source, "INSERT INTO RBT_LOG VALUES (1, 1001, 'TONE_A', 1, 1, '10.0.0.1')");
        execute(source, "INSERT INTO RBT_LOG VALUES (2, 1002, 'TONE_B', 3, 1, '10.0.0.2')");
        execute(source, "INSERT INTO RBT_LOG VALUES (3, 1003, 'IGNORED', 2, 1, '10.0.0.1')");
        execute(source, "INSERT INTO RBT_LOG VALUES (4, 1004, 'MISSING', 1, 1, '10.0.0.1')");
        commitSetupData();

        Path directory = Files.createTempDirectory("sync-result-");
        Path offset = directory.resolve("offset.txt");
        new DatabaseSynchronizer().synchronize(targetFactory, sourceFactory, 2,
                new OffsetStore(offset), Arrays.asList("10.0.0.1", "10.0.0.2"), SchemaCopyPlan.loadFromSource(sourceFactory));

        assertEquals("4", new String(Files.readAllBytes(offset)).trim());
        assertTrue(exists(target, "MAP_CP_RBT", "TONE_A"));
        assertTrue(exists(target, "TONELIST", "TONE_A"));
        assertTrue(exists(target, "MAP_CP_RBT", "TONE_B"));
        assertFalse(exists(target, "TONELIST", "TONE_B"));
        assertEquals(3, scalarInt(target, "SELECT COUNT(*) FROM TONELIST_SYNLOG"));
        assertEquals(2, scalarInt(target, "SELECT COUNT(*) FROM TONELIST_SYNLOG WHERE STATE = 1"));
        assertEquals(1, scalarInt(target, "SELECT COUNT(*) FROM TONELIST_SYNLOG WHERE STATE = 0"));
        assertEquals(1004, scalarInt(target, "SELECT TONE_ID FROM TONELIST_SYNLOG WHERE STATE = 0"));
    }

    @Test
    public void existingMapIsSkippedAndMissingOffsetDefaultsToZero() throws Exception {
        execute(target, "INSERT INTO MAP_CP_RBT VALUES (1, 'EXISTS', 'old')");
        execute(source, "INSERT INTO MAP_CP_RBT VALUES (2, 'EXISTS', 'new')");
        execute(source, "INSERT INTO TONELIST VALUES (2, 'EXISTS', 'source tone')");
        execute(source, "INSERT INTO RBT_LOG VALUES (10, 1010, 'EXISTS', 1, 1, '10.0.0.1')");
        commitSetupData();
        Path directory = Files.createTempDirectory("sync-result-");
        Path offset = directory.resolve("not-created.txt");

        new DatabaseSynchronizer().synchronize(targetFactory, sourceFactory, 100,
                new OffsetStore(offset), Arrays.asList("10.0.0.1"), SchemaCopyPlan.loadFromSource(sourceFactory));

        assertFalse(exists(target, "TONELIST", "EXISTS"));
        assertEquals(1, scalarInt(target, "SELECT COUNT(*) FROM TONELIST_SYNLOG WHERE STATE = 1"));
    }

    @Test
    public void onlyProcessesRbtLogsWhoseServerIsWhitelisted() throws Exception {
        execute(source, "INSERT INTO MAP_CP_RBT VALUES (20, 'ALLOWED', 'allowed')");
        execute(source, "INSERT INTO MAP_CP_RBT VALUES (21, 'BLOCKED', 'blocked')");
        execute(source, "INSERT INTO RBT_LOG VALUES (20, 2020, 'ALLOWED', 3, 1, '10.0.0.1')");
        execute(source, "INSERT INTO RBT_LOG VALUES (21, 2021, 'BLOCKED', 3, 1, '10.0.0.9')");
        commitSetupData();
        Path directory = Files.createTempDirectory("sync-whitelist-");

        new DatabaseSynchronizer().synchronize(targetFactory, sourceFactory, 100,
                new OffsetStore(directory.resolve("offset.txt")), Arrays.asList("10.0.0.1"), SchemaCopyPlan.loadFromSource(sourceFactory));

        assertTrue(exists(target, "MAP_CP_RBT", "ALLOWED"));
        assertFalse(exists(target, "MAP_CP_RBT", "BLOCKED"));
        assertEquals(1, scalarInt(target, "SELECT COUNT(*) FROM TONELIST_SYNLOG"));
    }

    private SessionFactory factory(String name) {
        Configuration configuration = new Configuration();
        configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
        configuration.setProperty("hibernate.connection.url", "jdbc:h2:mem:" + name + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        configuration.setProperty("hibernate.connection.username", "sa");
        configuration.setProperty("hibernate.connection.password", "");
        configuration.setProperty("hibernate.dialect", "org.hibernate.dialect.MySQLDialect");
        return configuration.buildSessionFactory();
    }

    private void createSchema(Session session) throws Exception {
        execute(session, "CREATE TABLE RBT_LOG (ID BIGINT PRIMARY KEY, TONE_ID BIGINT, TONE_CODE VARCHAR(50), ACTION_TYPE INT, RESULT INT, SERVER VARCHAR(50))");
        execute(session, "CREATE TABLE MAP_CP_RBT (ID BIGINT PRIMARY KEY, TONE_CODE VARCHAR(50) UNIQUE, DESCRIPTION VARCHAR(100))");
        execute(session, "CREATE TABLE TONELIST (ID BIGINT PRIMARY KEY, TONE_CODE VARCHAR(50) UNIQUE, DESCRIPTION VARCHAR(100))");
        execute(session, "CREATE TABLE TONELIST_SYNLOG (LOG_ID BIGINT AUTO_INCREMENT PRIMARY KEY, TONE_ID BIGINT, TONE_CODE VARCHAR(50), MOD_DATE TIMESTAMP, DESCRIPTION VARCHAR(1000), STATE INT)");
    }

    private void execute(Session session, String sql) throws Exception {
        try (Statement statement = session.connection().createStatement()) { statement.execute(sql); }
    }

    private void commitSetupData() throws Exception {
        source.connection().commit();
        target.connection().commit();
    }

    private boolean exists(Session session, String table, String toneCode) throws Exception {
        try (Statement statement = session.connection().createStatement();
             ResultSet result = statement.executeQuery("SELECT 1 FROM " + table + " WHERE TONE_CODE = '" + toneCode + "'")) {
            return result.next();
        }
    }

    private int scalarInt(Session session, String sql) throws Exception {
        try (Statement statement = session.connection().createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }
}
