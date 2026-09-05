package org.example.sync;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.example.utils.DbHelper;
import org.hibernate.SessionFactory;
import org.quartz.CronScheduleBuilder;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.quartz.JobBuilder;
import org.quartz.Scheduler;
import org.quartz.TriggerBuilder;
import org.quartz.impl.StdSchedulerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.StandardOpenOption;

/**
 * Main class for the synchronizer. Run with --once for an external scheduler.
 */
public final class SyncStart {
    private static final Logger logger = LogManager.getLogger(SyncStart.class);

    private static final File CONFIG = new File("etc/config.properties");
    private static final File LOG4J = new File("etc/log4j2.xml");
    private static final File OFFSET = new File("etc/offset.txt");
    private static final File LOCK = new File("etc/sync.lock");
    private static final File CONFIG_16M = new File("etc/hibernate_mysql_crbt16m.cfg.xml");
    private static final File CONFIG_21M = new File("etc/hibernate_mysql_crbt21m.cfg.xml");

    private SyncStart() {
    }

    public static void main(String[] args) throws Exception {
        logger.info("Starting CRBT synchronizer...");

        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        ctx.setConfigLocation(LOG4J.toURI());

        SyncConfig config = SyncConfig.load(CONFIG);
        if (config.getCronExpression().isEmpty()) {
            throw new IllegalArgumentException("CRONJOB is required unless --once is used");
        }
        Scheduler scheduler = StdSchedulerFactory.getDefaultScheduler();
        scheduler.scheduleJob(JobBuilder.newJob(SyncJob.class).withIdentity("crbt-sync-job").build(),
                TriggerBuilder.newTrigger().withIdentity("crbt-sync-trigger")
                        .withSchedule(CronScheduleBuilder.cronSchedule(config.getCronExpression())).build());
        scheduler.start();
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
        SyncConfig config = SyncConfig.load(CONFIG);
        SessionFactory target = null;
        SessionFactory source = null;
        try {
            target = DbHelper.buildSessionFactory(CONFIG_16M, null);
            source = DbHelper.buildSessionFactory(CONFIG_21M, null);
            new DatabaseSynchronizer().synchronize(target, source, config.getBatchSize(),
                    new OffsetStore(OFFSET.toPath()), config.getReportDirectory());
        } finally {
            if (source != null) {
                source.close();
            }
            if (target != null) {
                target.close();
            }
        }
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
