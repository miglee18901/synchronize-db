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
        logger.info("Starting ToolSyncTonelist21mTo16m...");

        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.setConfigLocation(LOG4J.toURI());

        SessionFactory target = null;
        SessionFactory source = null;
        Timer timer = null;
        try {
            SyncConfig config = getConfig();
            target = DbHelper.buildSessionFactory(CONFIG_16M);
            source = DbHelper.buildSessionFactory(CONFIG_21M);
            SchemaCopyPlan schemaCopyPlan = loadSchemaCopyPlan(source);
            timer = new Timer("tonelist-21m-to-16m-sync-timer");
            timer.schedule(new SyncTask(config, target, source, schemaCopyPlan), config.getDelayTimeMillis(), config.getPeriodTimeMillis());
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
                timer.cancel();
                close(source);
                close(target);
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
            try {
                new DatabaseSynchronizer().synchronize(target, source, config.getBatchSize(), new OffsetStore(OFFSET.toPath()), config.getServerIpWhitelist(), schemaCopyPlan);
            } catch (Exception exception) {
                LogManager.getLogger(SyncStart.class).error("Synchronization task failed", exception);
            }
        }
    }
}
