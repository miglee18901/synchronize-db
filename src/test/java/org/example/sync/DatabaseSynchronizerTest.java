package org.example.sync;

import org.hibernate.Session;
import org.hibernate.SessionFactory;
import org.hibernate.cfg.Configuration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseSynchronizerTest {
    private SessionFactory targetFactory;
    private SessionFactory sourceFactory;
    private Session target;
    private Session source;

    @BeforeEach
    void setUp() throws Exception {
        String suffix = String.valueOf(System.nanoTime());
        targetFactory = factory("sync_target_" + suffix);
        sourceFactory = factory("sync_source_" + suffix);
        target = targetFactory.openSession();
        source = sourceFactory.openSession();
        createSchema(target);
        createSchema(source);
    }

    @AfterEach
    void tearDown() {
        if (target != null) { target.close(); }
        if (source != null) { source.close(); }
        if (targetFactory != null) { targetFactory.close(); }
        if (sourceFactory != null) { sourceFactory.close(); }
    }

    @Test
    void copiesMapForAction3AndMapPlusToneListForAction1() throws Exception {
        execute(source, "INSERT INTO MAP_CP_RBT VALUES (101, 'TONE_A', 'map A')");
        execute(source, "INSERT INTO TONELIST VALUES (201, 'TONE_A', 'tone A')");
        execute(source, "INSERT INTO MAP_CP_RBT VALUES (102, 'TONE_B', 'map B')");
        execute(source, "INSERT INTO TONELIST VALUES (202, 'TONE_B', 'tone B')");
        execute(source, "INSERT INTO RBT_LOG VALUES (1, 'TONE_A', 1, 1)");
        execute(source, "INSERT INTO RBT_LOG VALUES (2, 'TONE_B', 3, 1)");
        execute(source, "INSERT INTO RBT_LOG VALUES (3, 'IGNORED', 2, 1)");
        execute(source, "INSERT INTO RBT_LOG VALUES (4, 'MISSING', 1, 1)");

        Path directory = Files.createTempDirectory("sync-result-");
        Path offset = directory.resolve("offset.txt");
        SyncRunResult result = new DatabaseSynchronizer().synchronize(targetFactory, sourceFactory, 2,
                new OffsetStore(offset), directory.toFile());

        assertEquals(4, result.getScanned());
        assertEquals(3, result.getSynchronizedRows());
        assertEquals(1, result.getErrors());
        assertEquals(4L, result.getFinalOffset());
        assertEquals("4", new String(Files.readAllBytes(offset)).trim());
        assertTrue(exists(target, "MAP_CP_RBT", "TONE_A"));
        assertTrue(exists(target, "TONELIST", "TONE_A"));
        assertTrue(exists(target, "MAP_CP_RBT", "TONE_B"));
        assertFalse(exists(target, "TONELIST", "TONE_B"));
    }

    @Test
    void existingMapIsSkippedAndMissingOffsetDefaultsToZero() throws Exception {
        execute(target, "INSERT INTO MAP_CP_RBT VALUES (1, 'EXISTS', 'old')");
        execute(source, "INSERT INTO MAP_CP_RBT VALUES (2, 'EXISTS', 'new')");
        execute(source, "INSERT INTO TONELIST VALUES (2, 'EXISTS', 'source tone')");
        execute(source, "INSERT INTO RBT_LOG VALUES (10, 'EXISTS', 1, 1)");
        Path directory = Files.createTempDirectory("sync-result-");
        Path offset = directory.resolve("not-created.txt");

        SyncRunResult result = new DatabaseSynchronizer().synchronize(targetFactory, sourceFactory, 100,
                new OffsetStore(offset), directory.toFile());

        assertEquals(0L, result.getInitialOffset());
        assertEquals(0, result.getSynchronizedRows());
        assertFalse(exists(target, "TONELIST", "EXISTS"));
    }

    private SessionFactory factory(String name) {
        Configuration configuration = new Configuration();
        configuration.setProperty("hibernate.connection.driver_class", "org.h2.Driver");
        configuration.setProperty("hibernate.connection.url", "jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
        configuration.setProperty("hibernate.connection.username", "sa");
        configuration.setProperty("hibernate.connection.password", "");
        configuration.setProperty("hibernate.dialect", "org.hibernate.dialect.H2Dialect");
        return configuration.buildSessionFactory();
    }

    private void createSchema(Session session) throws Exception {
        execute(session, "CREATE TABLE RBT_LOG (ID BIGINT PRIMARY KEY, TONE_CODE VARCHAR(50), ACTION_TYPE INT, RESULT INT)");
        execute(session, "CREATE TABLE MAP_CP_RBT (ID BIGINT PRIMARY KEY, TONE_CODE VARCHAR(50) UNIQUE, DESCRIPTION VARCHAR(100))");
        execute(session, "CREATE TABLE TONELIST (ID BIGINT PRIMARY KEY, TONE_CODE VARCHAR(50) UNIQUE, DESCRIPTION VARCHAR(100))");
    }

    private void execute(Session session, String sql) throws Exception {
        try (Statement statement = session.connection().createStatement()) { statement.execute(sql); }
    }

    private boolean exists(Session session, String table, String toneCode) throws Exception {
        try (Statement statement = session.connection().createStatement();
             ResultSet result = statement.executeQuery("SELECT 1 FROM " + table + " WHERE TONE_CODE = '" + toneCode + "'")) {
            return result.next();
        }
    }
}
