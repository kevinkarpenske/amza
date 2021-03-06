package com.jivesoftware.os.amza.lab.pointers;

import com.jivesoftware.os.amza.api.AmzaInterner;
import com.jivesoftware.os.amza.api.AmzaVersionConstants;
import com.jivesoftware.os.amza.api.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.api.wal.WALIndexProvider;
import com.jivesoftware.os.amza.lab.pointers.LABPointerIndexWALIndexName.Type;
import com.jivesoftware.os.jive.utils.collections.bah.LRUConcurrentBAHLinkedHash;
import com.jivesoftware.os.lab.LABEnvironment;
import com.jivesoftware.os.lab.LABStats;
import com.jivesoftware.os.lab.LabHeapPressure;
import com.jivesoftware.os.lab.guts.Leaps;
import com.jivesoftware.os.lab.guts.StripingBolBufferLocks;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;

/**
 *
 */
public class LABPointerIndexWALIndexProvider implements WALIndexProvider<LABPointerIndexWALIndex> {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();
    public static final String INDEX_CLASS_NAME = "lab";

    private final AmzaInterner amzaInterner;
    private final String name;
    private final LABEnvironment[] environments;
    private final LABPointerIndexConfig config;
    private final LRUConcurrentBAHLinkedHash<Leaps> leapCache;

    public LABPointerIndexWALIndexProvider(AmzaInterner amzaInterner,
        LABPointerIndexConfig config,
        ExecutorService heapThreadPool,
        ExecutorService schedulerThreadPool,
        ExecutorService compactorThreadPool,
        ExecutorService destroyThreadPool,
        String name,
        int numberOfStripes,
        File[] baseDirs) throws Exception {
        this.amzaInterner = amzaInterner;

        this.config = config;
        this.name = name;
        this.environments = new LABEnvironment[numberOfStripes];


        LABStats labStats = new LABStats(); // grr

        LabHeapPressure labHeapPressure = new LabHeapPressure(labStats, heapThreadPool,
            config.getHeapPressureName(),
            config.getGlobalMaxHeapPressureInBytes(),
            config.getGlobalBlockOnHeapPressureInBytes(),
            new AtomicLong(),
            LabHeapPressure.FreeHeapStrategy.mostBytesFirst);

        this.leapCache = LABEnvironment.buildLeapsCache((int) config.getLeapCacheMaxCapacity(), config.getConcurrency());

        for (int i = 0; i < environments.length; i++) {
            File active = new File(
                new File(
                    new File(baseDirs[i % baseDirs.length], AmzaVersionConstants.LATEST_VERSION),
                    INDEX_CLASS_NAME),
                String.valueOf(i));
            if (!active.exists() && !active.mkdirs()) {
                throw new RuntimeException("Failed while trying to mkdirs for " + active);
            }
            this.environments[i] = new LABEnvironment(labStats, schedulerThreadPool,
                compactorThreadPool,
                destroyThreadPool,
                null,
                active,
                labHeapPressure,
                config.getMinMergeDebt(),
                config.getMaxMergeDebt(),
                leapCache,
                new StripingBolBufferLocks(1024),
                false,
                true);

            File[] files = active.listFiles();
            if (files != null) {
                for (File file : files) {
                    if (file.isDirectory()) {
                        File dest = convertBase64toPartitionVersion(file);
                        if (dest != null && !file.renameTo(dest)) {
                            throw new IOException("Failed rename of " + file + " to " + dest);
                        }
                    }
                }
            }

            files = active.listFiles();
            if (files != null) {
                for (File file : files) {
                    String[] split = file.getName().split("-");
                    if (split.length == 3) {
                        try {
                            long partitionVersion = Long.parseLong(split[2]);
                            long h = hash(partitionVersion);

                            File parent = new File(active, String.valueOf(h % 1024));
                            if (!parent.mkdirs() && !parent.exists()) {
                                throw new IOException("Failed to mkdirs for " + parent);
                            }

                            File dest = new File(parent, file.getName());
                            if (!file.renameTo(dest)) {
                                throw new IOException("Failed to move " + file + " to " + dest);
                            }

                            LOG.info("We hash repaired {} to {}", file, dest);
                        } catch (NumberFormatException e) {
                            LOG.info("Skipped repair for " + file);
                        }
                    }
                }
            }
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public void start() {
        leapCache.start(name, config.getLeapCacheCleanupIntervalInMillis(), new LRUConcurrentBAHLinkedHash.CleanerExceptionCallback() {
            @Override
            public boolean exception(Throwable thrwbl) {
                throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
            }
        });
    }

    @Override
    public void stop() {
        leapCache.stop();
    }

    @Override
    public LABPointerIndexWALIndex createIndex(VersionedPartitionName versionedPartitionName, int maxValueSizeInIndex, int stripe) throws Exception {
        int modulo = (int) (hash(versionedPartitionName.getPartitionVersion()) % 1024);
        LABPointerIndexWALIndexName indexName = new LABPointerIndexWALIndexName(modulo,
            Type.active,
            String.valueOf(versionedPartitionName.getPartitionVersion()));
        //TODO config flush interval
        return new LABPointerIndexWALIndex(name,
            maxValueSizeInIndex,
            versionedPartitionName,
            environments,
            stripe,
            indexName,
            config);
    }

    @Override
    public void deleteIndex(VersionedPartitionName versionedPartitionName, int stripe) throws Exception {
        int modulo = (int) (hash(versionedPartitionName.getPartitionVersion()) % 1024);
        LABPointerIndexWALIndexName name = new LABPointerIndexWALIndexName(modulo,
            LABPointerIndexWALIndexName.Type.active,
            String.valueOf(versionedPartitionName.getPartitionVersion()));
        LABEnvironment env = environments[stripe];
        for (LABPointerIndexWALIndexName n : name.all()) {
            env.remove(n.getPrimaryName(), true);
            LOG.info("Removed database: {}", n.getPrimaryName());
            env.remove(n.getPrefixName(), true);
            LOG.info("Removed database: {}", n.getPrefixName());
        }
    }

    @Override
    public void flush(Iterable<LABPointerIndexWALIndex> indexes, boolean fsync) throws Exception {
        for (LABPointerIndexWALIndex index : indexes) {
            index.flush(fsync); // So call me maybe?
        }
    }

    private final static long randMult = 0x5DEECE66DL;
    private final static long randAdd = 0xBL;
    private final static long randMask = (1L << 48) - 1;

    private static long hash(long partitionVersion) {
        long x = (partitionVersion * randMult + randAdd) & randMask;
        long h = Math.abs(x >>> (16));
        if (h >= 0) {
            return h;
        } else {
            return Long.MAX_VALUE;
        }
    }

    private File convertBase64toPartitionVersion(File file) throws Exception {

        String filename = file.getName();
        int firstHyphen = filename.indexOf('-');
        int secondHyphen = filename.indexOf('-', firstHyphen + 1);
        if (firstHyphen != -1 && secondHyphen != -1) {
            String base = filename.substring(0, secondHyphen);
            String partition = filename.substring(secondHyphen + 1);
            try {
                long partitionVersion = Long.parseLong(partition);
                LOG.info("Did not repair partition version {}", partitionVersion);
            } catch (NumberFormatException e) {
                VersionedPartitionName vpn = amzaInterner.internVersionedPartitionNameBase64(partition);
                LOG.info("We will repair partition {}", vpn);
                return new File(file.getParent(), base + "-" + String.valueOf(vpn.getPartitionVersion()));
            }
        }
        return null;
    }

}
