package org.example.sync.scheduler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.example.sync.config.SyncConfig;
import org.example.sync.copy.SchemaCopyPlan;
import org.example.utils.DbHelper;
import org.hibernate.SessionFactory;

import java.io.File;
import java.io.IOException;
import java.util.Timer;
import java.util.TimerTask;

public final class SyncStart {
    private static final Logger logger = LogManager.getLogger(SyncStart.class);

    private static final File CONFIG = new File("etc/config.properties");
    private static final File LOG4J = new File("etc/log4j2.xml");
    private static final File OFFSET = new File("etc/offset.txt");
    private static final File CONFIG_16M = new File("etc/hibernate_mysql_crbt16m.cfg.xml");
    private static final File CONFIG_21M = new File("etc/hibernate_mysql_crbt21m.cfg.xml");
    private static volatile SyncConfig syncConfig;

    private SyncStart() {
    }

    public static void main(String[] args) {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.setConfigLocation(LOG4J.toURI());
        logger.info("Starting ToolSyncTonelist21mTo16m...");
        logger.debug("[STARTUP] Log4j configuration loaded from {}", LOG4J.getAbsolutePath());

        SessionFactory target = null;
        SessionFactory source = null;
        Timer timer = null;
        try {
            SyncConfig config = getConfig();
            logger.debug("[STARTUP] Configuration loaded: batchSize={}, delayMillis={}, periodMillis={}, serverWhitelist={}",
                    config.getBatchSize(), config.getDelayTimeMillis(), config.getPeriodTimeMillis(), config.getServerIpWhitelist());
            logger.debug("[STARTUP] File directories loaded: TEMP={}, WAV={}, TEMP_MUSIC={}, AMR={}",
                    config.getTempDirectory(), config.getWavDirectory(), config.getTempMusicDirectory(),
                    config.getAmrDirectory());

            target = DbHelper.buildSessionFactory(CONFIG_16M);
            logger.debug("[STARTUP] CRBT16M target SessionFactory created");

            source = DbHelper.buildSessionFactory(CONFIG_21M);
            logger.debug("[STARTUP] CRBT21M source SessionFactory created");

            logger.debug("[STARTUP] Loading source schema copy plan");
            SchemaCopyPlan schemaCopyPlan = loadSchemaCopyPlan(source);
            logger.debug("[STARTUP] Schema copy plan loaded: MAP_CP_RBT columns={}, TONELIST columns={}",
                    schemaCopyPlan.getMapCpRbt().getColumns().size(), schemaCopyPlan.getTonelist().getColumns().size());

            timer = new Timer("tonelist-21m-to-16m-sync-timer");
            timer.schedule(new SyncTask(config, target, source, schemaCopyPlan), config.getDelayTimeMillis(), config.getPeriodTimeMillis());
            logger.debug("[SCHEDULER] Task scheduled: initialDelayMillis={}, periodMillis={}",
                    config.getDelayTimeMillis(), config.getPeriodTimeMillis());
            addShutdownHook(timer, target, source);
        } catch (Exception e) {
            if (timer != null) {
                timer.cancel();
            }
            close(source);
            close(target);
            logger.error("Error starting ToolSyncTonelist21mTo16m: {}", e.getMessage(), e);
        }

        logger.info("ToolSyncTonelist21mTo16m started.");
    }

    private static SyncConfig getConfig() throws IOException {
        SyncConfig config = syncConfig;
        if (config == null) {
            synchronized (SyncStart.class) {
                config = syncConfig;
                if (config == null) {
                    config = SyncConfig.load(CONFIG);
                    syncConfig = config;
                }
            }
        }
        return config;
    }

    private static SchemaCopyPlan loadSchemaCopyPlan(SessionFactory source) throws IOException {
        try {
            return SchemaCopyPlan.loadFromSource(source);
        } catch (Exception exception) {
            throw new IOException("Cannot start scheduler because source table columns cannot be loaded", exception);
        }
    }

    private static void addShutdownHook(final Timer timer, final SessionFactory target, final SessionFactory source) {
        Runtime.getRuntime().addShutdownHook(new Thread("crbt-sync-shutdown") {
            @Override
            public void run() {
                logger.debug("[SHUTDOWN] Cancelling scheduler and closing database SessionFactories");
                timer.cancel();
                close(source);
                close(target);
                logger.debug("[SHUTDOWN] Resources closed");
            }
        });
    }

    private static void close(SessionFactory sessionFactory) {
        if (sessionFactory != null) {
            sessionFactory.close();
        }
    }

    public static final class SyncTask extends TimerTask {
        private final SyncConfig config;
        private final SessionFactory target;
        private final SessionFactory source;
        private final SchemaCopyPlan schemaCopyPlan;

        public SyncTask(SyncConfig config, SessionFactory target, SessionFactory source, SchemaCopyPlan schemaCopyPlan) {
            this.config = config;
            this.target = target;
            this.source = source;
            this.schemaCopyPlan = schemaCopyPlan;
        }

        @Override
        public void run() {
            long startedAt = System.currentTimeMillis();
            LogManager.getLogger(SyncStart.class).debug("[SCHEDULER] Synchronization task started");
            try {
                new DatabaseSynchronizer(config.getTempDirectory(), config.getWavDirectory(),
                        config.getTempMusicDirectory(), config.getAmrDirectory())
                        .synchronize(target, source, config.getBatchSize(), new OffsetStore(OFFSET.toPath()),
                                config.getServerIpWhitelist(), schemaCopyPlan);
            } catch (Exception exception) {
                LogManager.getLogger(SyncStart.class).error("Synchronization task failed", exception);
            } finally {
                LogManager.getLogger(SyncStart.class).debug("[SCHEDULER] Synchronization task finished in {} ms",
                        System.currentTimeMillis() - startedAt);
            }
        }
    }
}
