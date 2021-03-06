package com.jivesoftware.os.amza.service.storage.delta;

import com.google.common.collect.Maps;
import com.google.common.io.Files;
import com.jivesoftware.os.amza.api.AmzaInterner;
import com.jivesoftware.os.amza.api.partition.PartitionName;
import com.jivesoftware.os.amza.api.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.api.stream.RowType;
import com.jivesoftware.os.amza.api.wal.PrimaryRowMarshaller;
import com.jivesoftware.os.amza.api.wal.WALKey;
import com.jivesoftware.os.amza.api.wal.WALValue;
import com.jivesoftware.os.amza.api.IoStats;
import com.jivesoftware.os.amza.service.storage.HighwaterRowMarshaller;
import com.jivesoftware.os.amza.service.storage.binary.BinaryHighwaterRowMarshaller;
import com.jivesoftware.os.amza.service.storage.binary.BinaryPrimaryRowMarshaller;
import com.jivesoftware.os.amza.service.storage.binary.BinaryRowIOProvider;
import com.jivesoftware.os.jive.utils.ordered.id.ConstantWriterIdProvider;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProviderImpl;
import java.io.File;
import java.util.Map;
import org.testng.Assert;
import org.testng.annotations.Test;

/**
 * @author jonathan.colt
 */
public class DeltaWALNGTest {

    @Test
    public void testLoad() throws Exception {
        AmzaInterner amzaInterner = new AmzaInterner();
        VersionedPartitionName versionedPartitionName = new VersionedPartitionName(new PartitionName(true, "test".getBytes(), "test".getBytes()),
            VersionedPartitionName.STATIC_VERSION);
        File tmp = Files.createTempDir();
        final PrimaryRowMarshaller primaryRowMarshaller = new BinaryPrimaryRowMarshaller();
        final HighwaterRowMarshaller<byte[]> highwaterRowMarshaller = new BinaryHighwaterRowMarshaller(amzaInterner);
        String[] workingDirs = new String[] { tmp.getAbsolutePath() };

        IoStats ioStats = new IoStats();
        BinaryRowIOProvider binaryRowIOProvider = new BinaryRowIOProvider(4_096, 64, false);

        OrderIdProviderImpl ids = new OrderIdProviderImpl(new ConstantWriterIdProvider(1));
        DeltaWALFactory deltaWALFactory = new DeltaWALFactory(ids, tmp, binaryRowIOProvider, primaryRowMarshaller, highwaterRowMarshaller, 1);

        DeltaWAL deltaWAL = deltaWALFactory.create(ioStats, -1);

        Map<WALKey, WALValue> apply1 = Maps.newLinkedHashMap();
        for (int i = 0; i < 10; i++) {
            byte[] bytes = (i + "k").getBytes();
            long timestampAndVersion = ids.nextId();
            apply1.put(new WALKey(bytes, bytes), new WALValue(RowType.primary, (i + "v").getBytes(), timestampAndVersion, false, timestampAndVersion));
        }
        DeltaWAL.DeltaWALApplied update1 = deltaWAL.update(ioStats, RowType.primary, versionedPartitionName, apply1, null);
        for (DeltaWAL.KeyValueHighwater kvh : update1.keyValueHighwaters) {
            System.out.println("update1 k=" + new String(kvh.key) + " v=" + new WALValue(kvh.rowType, kvh.value, kvh.valueTimestamp, kvh.valueTombstone,
                kvh.valueVersion));
        }

        Map<WALKey, WALValue> apply2 = Maps.newLinkedHashMap();
        for (int i = 0; i < 10; i++) {
            byte[] bytes = (i + "k").getBytes();
            long timestampAndVersion = ids.nextId();
            apply2.put(new WALKey(bytes, bytes), new WALValue(RowType.primary, (i + "v").getBytes(), timestampAndVersion, false, timestampAndVersion));
        }
        DeltaWAL.DeltaWALApplied update2 = deltaWAL.update(ioStats, RowType.primary, versionedPartitionName, apply2, null);
        for (DeltaWAL.KeyValueHighwater kvh : update2.keyValueHighwaters) {
            System.out.println("update2 k=" + new String(kvh.key) + " v=" + new WALValue(kvh.rowType, kvh.value, kvh.valueTimestamp, kvh.valueTombstone,
                kvh.valueVersion));
        }

        deltaWAL.load(ioStats, (long rowFP, long rowTxId, RowType rowType, byte[] rawRow) -> {
            if (rowType == RowType.primary) {
                primaryRowMarshaller.fromRows(fpRowStream -> fpRowStream.stream(rowFP, rowType, rawRow),
                    (fp, rowType2, prefix, key, value, valueTimestamp, valueTombstoned, valueVersion) -> {
                        System.out.println("rfp=" + rowFP + " rid" + rowTxId + " rt=" + rowType2
                            + " key=" + new String(key) + " value=" + new String(value)
                            + " ts=" + valueTimestamp + " tombstone=" + valueTombstoned);
                        return true;
                    });
            }
            return true;
        });

        for (int i = 0; i < update1.fps.length; i++) {
            DeltaWAL.KeyValueHighwater kvh = update1.keyValueHighwaters[i];
            System.out.println(update1.fps[i] + " hydrate k=" + new String(kvh.key)
                + " v=" + new WALValue(kvh.rowType, kvh.value, kvh.valueTimestamp, kvh.valueTombstone, kvh.valueVersion));
            long fp1 = update1.fps[i];
            deltaWAL.hydrate(new IoStats(), fpStream -> fpStream.stream(fp1),
                (fp, rowType, prefix, key, value, valueTimestamp, valueTombstone, valueVersion) -> {
                    System.out.println(fp + " hydrated:" + new WALValue(rowType, value, valueTimestamp, valueTombstone, valueVersion));
                    Assert.assertEquals(
                        new WALValue(rowType, value, valueTimestamp, valueTombstone, valueVersion),
                        apply1.get(new WALKey(kvh.prefix, kvh.key)));
                    return true;
                });
        }

        for (int i = 0; i < update2.fps.length; i++) {
            DeltaWAL.KeyValueHighwater kvh = update2.keyValueHighwaters[i];
            System.out.println(update2.fps[i] + " hydrate k=" + new String(kvh.key)
                + " v=" + new WALValue(kvh.rowType, kvh.value, kvh.valueTimestamp, kvh.valueTombstone, kvh.valueVersion));
            long fp2 = update2.fps[i];
            deltaWAL.hydrate(new IoStats(), fpStream -> fpStream.stream(fp2),
                (fp, rowType, prefix, key, value, valueTimestamp, valueTombstone, valueVersion) -> {
                    System.out.println(fp + " hydrated:" + new WALValue(rowType, value, valueTimestamp, valueTombstone, valueVersion));
                    Assert.assertEquals(
                        new WALValue(rowType, value, valueTimestamp, valueTombstone, valueVersion),
                        apply2.get(new WALKey(kvh.prefix, kvh.key)));
                    return true;
                });

        }
    }
}
