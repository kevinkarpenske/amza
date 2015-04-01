package com.jivesoftware.os.amza.berkeleydb;

import com.jivesoftware.os.amza.shared.PrimaryIndexDescriptor;
import com.jivesoftware.os.amza.shared.RegionName;
import com.jivesoftware.os.amza.shared.SecondaryIndexDescriptor;
import com.jivesoftware.os.amza.shared.WALIndex;
import com.jivesoftware.os.amza.shared.WALKey;
import com.jivesoftware.os.amza.shared.WALScan;
import com.jivesoftware.os.amza.shared.WALValue;
import com.jivesoftware.os.amza.shared.filer.UIO;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import com.sleepycat.je.Cursor;
import com.sleepycat.je.Database;
import com.sleepycat.je.DatabaseConfig;
import com.sleepycat.je.DatabaseEntry;
import com.sleepycat.je.DiskOrderedCursor;
import com.sleepycat.je.Environment;
import com.sleepycat.je.EnvironmentConfig;
import com.sleepycat.je.LockMode;
import com.sleepycat.je.OperationStatus;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.apache.commons.io.FileUtils;

/**
 * @author jonathan.colt
 */
public class BerkeleyDBWALIndex implements WALIndex {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private static final int numPermits = 1024;
    private final Semaphore lock = new Semaphore(numPermits, true);

    private final RegionName regionName;
    private final File dir;
    private Environment environment;
    private Database database;

    public BerkeleyDBWALIndex(File dir, RegionName regionName) throws Exception {
        this.dir = dir;
        this.regionName = regionName;
        // Open the environment, creating one if it does not exist
        EnvironmentConfig envConfig = new EnvironmentConfig();
        envConfig.setAllowCreate(true);
        File active = new File(dir, "active");
        active.mkdirs();
        this.environment = new Environment(active, envConfig);

        // Open the database, creating one if it does not exist
        DatabaseConfig dbConfig = new DatabaseConfig();
        dbConfig.setAllowCreate(true);
        this.database = environment.openDatabase(null, regionName.getRegionName(), dbConfig);

        LOG.info("Opening " + active.getAbsolutePath() + " " + database.count());
    }

    private DatabaseEntry walValueToEntry(WALValue value) {
        byte[] valueBytes = value.getValue();
        long timestamp = value.getTimestampId();
        boolean tombstoned = value.getTombstoned();
        byte[] entryBytes = new byte[valueBytes.length + 8 + 1];
        System.arraycopy(valueBytes, 0, entryBytes, 0, valueBytes.length);
        UIO.longBytes(timestamp, entryBytes, valueBytes.length);
        entryBytes[valueBytes.length + 8] = tombstoned ? (byte) 1 : (byte) 0;
        return new DatabaseEntry(entryBytes);
    }

    private WALValue entryToWALValue(DatabaseEntry entry) {
        byte[] entryBytes = entry.getData();
        byte[] valueBytes = new byte[entryBytes.length - 8 - 1];
        System.arraycopy(entryBytes, 0, valueBytes, 0, valueBytes.length);
        long timestamp = UIO.bytesLong(entryBytes, valueBytes.length);
        boolean tombstoned = (entryBytes[valueBytes.length + 8] == (byte) 1);
        return new WALValue(valueBytes, timestamp, tombstoned);
    }

    @Override
    public void put(Collection<? extends Map.Entry<WALKey, WALValue>> entries) throws Exception {
        lock.acquire();
        try {
            for (Map.Entry<WALKey, WALValue> entry : entries) {
                database.put(null, new DatabaseEntry(entry.getKey().getKey()), walValueToEntry(entry.getValue()));
            }
        } finally {
            lock.release();
        }
    }

    @Override
    public List<WALValue> get(List<WALKey> keys) throws Exception {
        lock.acquire();
        try {
            List<WALValue> gots = new ArrayList<>(keys.size());
            DatabaseEntry value = new DatabaseEntry();
            for (WALKey key : keys) {
                OperationStatus status = database.get(null, new DatabaseEntry(key.getKey()), value, LockMode.READ_UNCOMMITTED);
                if (status == OperationStatus.SUCCESS) {
                    gots.add(entryToWALValue(value));
                } else {
                    gots.add(null);
                }
            }
            return gots;
        } finally {
            lock.release();
        }
    }

    @Override
    public List<Boolean> containsKey(List<WALKey> keys) throws Exception {
        lock.acquire();
        try {
            List<Boolean> contains = new ArrayList<>(keys.size());
            DatabaseEntry value = new DatabaseEntry();
            for (WALKey key : keys) {
                OperationStatus status = database.get(null, new DatabaseEntry(key.getKey()), value, LockMode.READ_UNCOMMITTED);
                if (status == OperationStatus.SUCCESS) {
                    contains.add(true);
                } else {
                    contains.add(false);
                }
            }
            return contains;
        } finally {
            lock.release();
        }
    }

    @Override
    public void remove(Collection<WALKey> keys) throws Exception {
        lock.acquire();
        try {
            for (WALKey key : keys) {
                database.delete(null, new DatabaseEntry(key.getKey()));
            }
        } finally {
            lock.release();
        }
    }

    @Override
    public boolean isEmpty() throws Exception {
        lock.acquire();
        DiskOrderedCursor cursor = null;
        try {
            cursor = database.openCursor(null);
            return (cursor.getNext(new DatabaseEntry(), new DatabaseEntry(), LockMode.READ_UNCOMMITTED) != OperationStatus.SUCCESS);
        } finally {
            lock.release();
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    @Override
    public void commit() {
    }

    @Override
    public void compact() {
    }

    public void close() throws Exception {
        lock.acquire(numPermits);
        try {
            database.close();
            database = null;
        } finally {
            lock.release(numPermits);
        }
    }

    @Override
    public void rowScan(final WALScan walScan) throws Exception {
        lock.acquire();
        Cursor cursor = null;
        try {
            cursor = database.openCursor(null, null);
            DatabaseEntry keyEntry = new DatabaseEntry();
            DatabaseEntry valueEntry = new DatabaseEntry();
            while (cursor.getNext(keyEntry, valueEntry, LockMode.READ_UNCOMMITTED) == OperationStatus.SUCCESS) {
                WALKey key = new WALKey(keyEntry.getData());
                WALValue value = entryToWALValue(valueEntry);
                if (!walScan.row(-1, key, value)) {
                    break;
                }
            }
        } finally {
            lock.release();
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    @Override
    synchronized public void rangeScan(WALKey from, WALKey to, final WALScan walScan) throws Exception {
        lock.acquire();
        Cursor cursor = null;
        try {
            cursor = database.openCursor(null, null);
            DatabaseEntry keyEntry = new DatabaseEntry(from.getKey());
            DatabaseEntry valueEntry = new DatabaseEntry();
            if (cursor.getSearchKeyRange(keyEntry, valueEntry, LockMode.READ_UNCOMMITTED) == OperationStatus.SUCCESS) {
                do {
                    WALKey key = new WALKey(keyEntry.getData());
                    if (key.compareTo(to) >= 0) {
                        break;
                    }
                    WALValue value = entryToWALValue(valueEntry);
                    if (!walScan.row(-1, key, value)) {
                        break;
                    }
                }
                while (cursor.getNext(keyEntry, valueEntry, LockMode.READ_UNCOMMITTED) == OperationStatus.SUCCESS);
            }
        } finally {
            lock.release();
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    @Override
    public CompactionWALIndex startCompaction() throws Exception {
        final File compacting = new File(dir, "compacting");
        FileUtils.deleteDirectory(compacting);

        final File compacted = new File(dir, "compacted");
        FileUtils.deleteDirectory(compacted);

        final File backup = new File(dir, "backup");
        FileUtils.deleteDirectory(backup);

        compacting.mkdirs();
        final BerkeleyDBWALIndex compactedWALIndex = new BerkeleyDBWALIndex(compacting, regionName);

        return new CompactionWALIndex() {

            @Override
            public void put(Collection<? extends Map.Entry<WALKey, WALValue>> entries) throws Exception {
                compactedWALIndex.put(entries);
            }

            @Override
            public void abort() throws Exception {
                try {
                    compactedWALIndex.close();
                } catch (IOException ex) {
                    throw new RuntimeException();
                }
            }

            @Override
            public void commit() throws Exception {
                lock.acquire(numPermits);
                try {
                    long countTs = System.currentTimeMillis();
                    long count = database.count();
                    LOG.info("Committing before swap: {} {} (counted={}ms)", new File(dir, "active"), count, (System.currentTimeMillis() - countTs));

                    database.close();
                    environment.close();
                    database = null;
                    environment = null;

                    FileUtils.moveDirectory(new File(compacting, "active"), compacted);
                    FileUtils.moveDirectory(new File(dir, "active"), backup);
                    FileUtils.moveDirectory(compacted, new File(dir, "active"));
                    FileUtils.deleteDirectory(backup);

                    environment = compactedWALIndex.environment;
                    database = compactedWALIndex.database;

                    countTs = System.currentTimeMillis();
                    count = database.count();
                    LOG.info("Committing after swap: {} {} (counted={}ms)", new File(dir, "active"), count, (System.currentTimeMillis() - countTs));
                } finally {
                    lock.release(numPermits);
                }
            }
        };
    }

    @Override
    public void updatedDescriptors(PrimaryIndexDescriptor primaryIndexDescriptor, SecondaryIndexDescriptor[] secondaryIndexDescriptors) {
    }
}
