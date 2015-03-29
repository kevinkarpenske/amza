package com.jivesoftware.os.amza.storage.binary;

import com.google.common.io.Files;
import com.jivesoftware.os.amza.shared.MemoryWALIndex;
import com.jivesoftware.os.amza.shared.RegionName;
import com.jivesoftware.os.amza.shared.RowsChanged;
import com.jivesoftware.os.amza.shared.WALIndex;
import com.jivesoftware.os.amza.shared.WALIndexProvider;
import com.jivesoftware.os.amza.shared.WALKey;
import com.jivesoftware.os.amza.shared.WALReplicator;
import com.jivesoftware.os.amza.shared.WALStorageUpateMode;
import com.jivesoftware.os.amza.shared.WALValue;
import com.jivesoftware.os.amza.storage.IndexedWAL;
import com.jivesoftware.os.filer.io.FilerIO;
import com.jivesoftware.os.jive.utils.ordered.id.ConstantWriterIdProvider;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProviderImpl;
import java.io.File;
import java.util.AbstractMap.SimpleEntry;
import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 *
 * @author jonathan.colt
 */
public class RowRegionNGTest {

    @Test(enabled = false)
    public void concurrencyTest() throws Exception {
        File walDir = Files.createTempDir();
        //RowIOProvider binaryRowIOProvider = new BufferedBinaryRowIOProvider();
        RowIOProvider binaryRowIOProvider = new BinaryRowIOProvider();
        final BinaryRowMarshaller rowMarshaller = new BinaryRowMarshaller();

        final WALIndexProvider indexProvider = new WALIndexProvider() {

            @Override
            public WALIndex createIndex(RegionName regionName) throws Exception {
                return new MemoryWALIndex();
            }
        };
        RegionName regionName = new RegionName(false, "ring", "booya");

        BinaryWALTx binaryWALTx = new BinaryWALTx(walDir, "booya", binaryRowIOProvider, rowMarshaller, indexProvider);

        final OrderIdProviderImpl idProvider = new OrderIdProviderImpl(new ConstantWriterIdProvider(1));
        final IndexedWAL indexedWAL = new IndexedWAL(regionName, idProvider, rowMarshaller, binaryWALTx, new WALReplicator() {

            @Override
            public void replicate(RowsChanged rowsChanged) throws Exception {
            }
        }, 1000, 1000);
        indexedWAL.load();

        final Random r = new Random();

        ScheduledExecutorService compact = Executors.newScheduledThreadPool(1);
        compact.scheduleAtFixedRate(new Runnable() {

            @Override
            public void run() {
                try {
                    indexedWAL.compactTombstone(0, Long.MAX_VALUE);
                } catch (Exception x) {
                    x.printStackTrace();
                }
            }
        }, 1, 1, TimeUnit.SECONDS);

        int numThreads = 1;
        ExecutorService writers = Executors.newFixedThreadPool(numThreads);
        for (int i = 0; i < numThreads; i++) {
            writers.submit(new Runnable() {

                @Override
                public void run() {
                    for (int i = 1; i < 1_000; i++) {
                        try {
                            addBatch(r, idProvider, indexedWAL, i, 0, 10);
                            if (i % 1000 == 0) {
                                System.out.println(Thread.currentThread() + " batch:" + i);
                            }
                        } catch (Throwable x) {
                            x.printStackTrace();
                        }
                    }
                }
            });
        }

        writers.shutdown();
        writers.awaitTermination(1, TimeUnit.DAYS);

        compact.shutdownNow();

//        addBatch(r, idProvider, table, 10, 0, 10);
//        table.compactTombstone(0);
//        addBatch(r, idProvider, table, 10, 10, 20);
//        table.compactTombstone(0);
//
//        table.rowScan(new RowScan<Exception>() {
//
//            @Override
//            public boolean row(long transactionId, RowIndexKey key, RowIndexValue value) throws Exception {
//                System.out.println(FilerIO.bytesInt(key.getKey()));
//                return true;
//            }
//        });
    }

    private void addBatch(Random r, OrderIdProviderImpl idProvider, IndexedWAL indexedWAL, int range, int start, int length) throws Exception {
        MemoryWALIndex updates = new MemoryWALIndex();
        for (int i = start; i < start + length; i++) {
            WALKey key = new WALKey(FilerIO.intBytes(r.nextInt(range)));
            updates.put(Arrays.asList(new SimpleEntry<>(key, new WALValue(FilerIO.intBytes(i), idProvider.nextId(), false))));
        }
        indexedWAL.update(WALStorageUpateMode.updateThenReplicate, updates);
    }

    @Test
    public void eventualConsitencyTest() throws Exception {
        File walDir = Files.createTempDir();
        //RowIOProvider binaryRowIOProvider = new BufferedBinaryRowIOProvider();
        RowIOProvider binaryRowIOProvider = new BinaryRowIOProvider();
        final BinaryRowMarshaller rowMarshaller = new BinaryRowMarshaller();

        final WALIndexProvider indexProvider = new WALIndexProvider() {

            @Override
            public WALIndex createIndex(RegionName regionName) throws Exception {
                return new MemoryWALIndex();
            }
        };
        RegionName regionName = new RegionName(false, "ring", "booya");

        BinaryWALTx binaryWALTx = new BinaryWALTx(walDir, "booya", binaryRowIOProvider, rowMarshaller, indexProvider);

        OrderIdProviderImpl idProvider = new OrderIdProviderImpl(new ConstantWriterIdProvider(1));
        IndexedWAL indexedWAL = new IndexedWAL(regionName, idProvider, rowMarshaller, binaryWALTx, new WALReplicator() {

            @Override
            public void replicate(RowsChanged rowsChanged) throws Exception {
            }
        }, 1000, 1000);
        indexedWAL.load();
        WALValue value = indexedWAL.get(rk(1));
        Assert.assertNull(value);

        int t = 10;
        update(indexedWAL, k(1), v("hello"), t, false);

        value = indexedWAL.get(rk(1));
        Assert.assertFalse(value.getTombstoned());
        Assert.assertEquals(value.getTimestampId(), t);
        Assert.assertEquals(new String(value.getValue()), "hello");

        t++;
        update(indexedWAL, k(1), v("hello2"), t, false);
        value = indexedWAL.get(rk(1));
        Assert.assertFalse(value.getTombstoned());
        Assert.assertEquals(value.getTimestampId(), t);
        Assert.assertEquals(new String(value.getValue()), "hello2");

        update(indexedWAL, k(1), v("hello3"), t, false);
        value = indexedWAL.get(rk(1));
        Assert.assertFalse(value.getTombstoned());
        Assert.assertEquals(value.getTimestampId(), t);
        Assert.assertEquals(new String(value.getValue()), "hello2");

        update(indexedWAL, k(1), v("fail"), t - 1, false);
        value = indexedWAL.get(rk(1));
        Assert.assertFalse(value.getTombstoned());
        Assert.assertEquals(value.getTimestampId(), t);
        Assert.assertEquals(new String(value.getValue()), "hello2");

        t++;
        update(indexedWAL, k(1), v("deleted"), t, false);
        value = indexedWAL.get(rk(1));
        Assert.assertFalse(value.getTombstoned());
        Assert.assertEquals(value.getTimestampId(), t);
        Assert.assertEquals(new String(value.getValue()), "deleted");

        update(indexedWAL, k(1), v("fail"), t - 1, false);
        value = indexedWAL.get(rk(1));
        Assert.assertFalse(value.getTombstoned());
        Assert.assertEquals(value.getTimestampId(), t);
        Assert.assertEquals(new String(value.getValue()), "deleted");

        t++;
        update(indexedWAL, k(1), v("hello4"), t, false);
        value = indexedWAL.get(rk(1));
        Assert.assertFalse(value.getTombstoned());
        Assert.assertEquals(value.getTimestampId(), t);
        Assert.assertEquals(new String(value.getValue()), "hello4");
    }

    public WALKey rk(int key) {
        return new WALKey(k(key));
    }

    public byte[] k(int key) {
        return FilerIO.intBytes(key);
    }

    public byte[] v(String value) {
        return value.getBytes();
    }

    private void update(IndexedWAL indexedWAL, byte[] key, byte[] value, long timestamp, boolean remove) throws Exception {
        MemoryWALIndex updates = new MemoryWALIndex();
        updates.put(Arrays.asList(new SimpleEntry<>(new WALKey(key), new WALValue(value, timestamp, remove))));
        indexedWAL.update(WALStorageUpateMode.updateThenReplicate, updates);
    }
}
