package org.example.sync.scheduler;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.example.sync.config.SyncConfig;
import org.example.sync.copy.SchemaCopyPlan;
import org.example.sync.core.DatabaseSynchronizer;
import org.example.sync.offset.OffsetStore;
import org.example.utils.DbHelper;
import org.hibernate.SessionFactory;
import org.quartz.*;
import org.quartz.impl.StdSchedulerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;

public final class SyncStart {
    private static final Logger logger = LogManager.getLogger(SyncStart.class);

    private static final File CONFIG = new File("etc/config.properties");
    private static final File LOG4J = new File("etc/log4j2.xml");
    private static final File OFFSET = new File("etc/offset.txt");
    private static final File LOCK = new File("etc/sync.lock");
    private static final File CONFIG_16M = new File("etc/hibernate_mysql_crbt16m.cfg.xml");
    private static final File CONFIG_21M = new File("etc/hibernate_mysql_crbt21m.cfg.xml");
    private static volatile SyncConfig syncConfig;
    private static volatile SchemaCopyPlan schemaCopyPlan;

    private SyncStart() {
    }

    public static void main(String[] args) {
        logger.info("Starting CRBT synchronizer...");

        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.setConfigLocation(LOG4J.toURI());

        try {
            SyncConfig config = getConfig();
            if (config.getCronExpression().isEmpty()) {
                throw new IllegalArgumentException("CRONJOB is required unless --once is used");
            }
            getSchemaCopyPlan();
            Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
            scheduler.scheduleJob(JobBuilder.newJob(SyncJob.class).withIdentity("crbt-sync-job").build(),
                    TriggerBuilder.newTrigger().withIdentity("crbt-sync-trigger")
                            .withSchedule(CronScheduleBuilder.cronSchedule(config.getCronExpression())).build());
            scheduler.start();
        } catch (Exception e) {
            logger.error("Error starting CRBT synchronizer: {}", e.getMessage(), e);
        }

        logger.info("Stop CRBT synchronizer!");
    }

    public static void runOnce() throws IOException {
        try (FileChannel channel = FileChannel.open(LOCK.toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException ignored) {
                lock = null;
            }
            if (lock == null) {
                LogManager.getLogger(SyncStart.class).warn("A synchronization run is already active; this trigger is skipped.");
                return;
            }
            try {
                execute();
            } finally {
                lock.release();
            }
        }
    }

    private static void execute() throws IOException {
        SyncConfig config = getConfig();
        SessionFactory target = null;
        SessionFactory source = null;
        try {
            target = DbHelper.buildSessionFactory(CONFIG_16M);
            source = DbHelper.buildSessionFactory(CONFIG_21M);
            new DatabaseSynchronizer().synchronize(target, source, config.getBatchSize(), new OffsetStore(OFFSET.toPath()),
                    config.getReportDirectory(), getSchemaCopyPlan());
        } finally {
            if (source != null) {
                source.close();
            }
            if (target != null) {
                target.close();
            }
        }
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

    private static SchemaCopyPlan getSchemaCopyPlan() throws IOException {
        SchemaCopyPlan plan = schemaCopyPlan;
        if (plan == null) {
            synchronized (SyncStart.class) {
                plan = schemaCopyPlan;
                if (plan == null) {
                    SessionFactory source = null;
                    try {
                        source = DbHelper.buildSessionFactory(CONFIG_21M);
                        plan = SchemaCopyPlan.loadFromSource(source);
                        schemaCopyPlan = plan;
                    } catch (Exception exception) {
                        throw new IOException("Cannot start scheduler because source table columns cannot be loaded", exception);
                    } finally {
                        if (source != null) {
                            source.close();
                        }
                    }
                }
            }
        }
        return plan;
    }

    @DisallowConcurrentExecution
    public static final class SyncJob implements Job {
        @Override
        public void execute(JobExecutionContext context) throws JobExecutionException {
            try {
                runOnce();
            } catch (Exception exception) {
                throw new JobExecutionException(exception, false);
            }
        }
    }
}
