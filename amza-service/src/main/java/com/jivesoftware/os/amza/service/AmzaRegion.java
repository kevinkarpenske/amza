/*
 * Copyright 2013 Jive Software, Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.jivesoftware.os.amza.service;

import com.jivesoftware.os.amza.service.replication.RegionStripe;
import com.jivesoftware.os.amza.service.storage.RowStoreUpdates;
import com.jivesoftware.os.amza.service.storage.RowsStorageUpdates;
import com.jivesoftware.os.amza.shared.RegionName;
import com.jivesoftware.os.amza.shared.RowStream;
import com.jivesoftware.os.amza.shared.Scan;
import com.jivesoftware.os.amza.shared.WALKey;
import com.jivesoftware.os.amza.shared.WALReplicator;
import com.jivesoftware.os.amza.shared.WALValue;
import com.jivesoftware.os.amza.shared.stats.AmzaStats;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProvider;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map.Entry;
import org.apache.commons.lang.mutable.MutableBoolean;
import org.apache.commons.lang.mutable.MutableInt;

public class AmzaRegion {

    private final AmzaStats amzaStats;
    private final OrderIdProvider orderIdProvider;
    private final RegionName regionName;
    private final WALReplicator replicator;
    private final RegionStripe regionStripe;

    public AmzaRegion(AmzaStats amzaStats,
        OrderIdProvider orderIdProvider,
        RegionName regionName,
        WALReplicator replicator,
        RegionStripe regionStripe) {

        this.amzaStats = amzaStats;
        this.orderIdProvider = orderIdProvider;
        this.regionName = regionName;
        this.replicator = replicator;
        this.regionStripe = regionStripe;
    }

    public RegionName getRegionName() {
        return regionName;
    }

    public WALKey set(WALKey key, byte[] value) throws Exception {
        if (value == null) {
            throw new IllegalStateException("Value cannot be null.");
        }
        long timestamp = orderIdProvider.nextId();
        RowStoreUpdates tx = new RowStoreUpdates(amzaStats, regionName, regionStripe, new RowsStorageUpdates(regionName, regionStripe));
        tx.put(key, new WALValue(value, timestamp, false));
        tx.commit(replicator);
        return key;
    }

    public WALKey setValue(WALKey key, WALValue value) throws Exception {
        if (value == null) {
            throw new IllegalStateException("Value cannot be null.");
        }
        RowStoreUpdates tx = new RowStoreUpdates(amzaStats, regionName, regionStripe, new RowsStorageUpdates(regionName, regionStripe));
        tx.put(key, value);
        tx.commit(replicator);
        return key;
    }

    public void set(Iterable<Entry<WALKey, byte[]>> entries) throws Exception {
        long timestamp = orderIdProvider.nextId();
        RowStoreUpdates tx = new RowStoreUpdates(amzaStats, regionName, regionStripe, new RowsStorageUpdates(regionName, regionStripe));
        for (Entry<WALKey, byte[]> e : entries) {
            WALKey k = e.getKey();
            byte[] v = e.getValue();
            if (v == null) {
                throw new IllegalStateException("Value cannot be null.");
            }
            tx.put(k, new WALValue(v, timestamp, false));
        }
        tx.commit(replicator);
    }

    public void setValues(Iterable<Entry<WALKey, WALValue>> entries) throws Exception {
        RowStoreUpdates tx = new RowStoreUpdates(amzaStats, regionName, regionStripe, new RowsStorageUpdates(regionName, regionStripe));
        for (Entry<WALKey, WALValue> e : entries) {
            WALKey k = e.getKey();
            WALValue v = e.getValue();
            if (v == null) {
                throw new IllegalStateException("Value cannot be null.");
            }
            tx.put(k, v);
        }
        tx.commit(replicator);
    }

    public byte[] get(WALKey key) throws Exception {
        WALValue got = regionStripe.get(regionName, key);
        if (got == null) {
            return null;
        }
        if (got.getTombstoned()) {
            return null;
        }
        return got.getValue();
    }

    public WALValue getValue(WALKey key) throws Exception {
        return regionStripe.get(regionName, key);
    }

    public List<byte[]> get(List<WALKey> keys) throws Exception {
        List<byte[]> values = new ArrayList<>();
        for (WALKey key : keys) {
            values.add(get(key));
        }
        return values;
    }

    public void get(Iterable<WALKey> keys, Scan<WALValue> valuesStream) throws Exception {
        for (final WALKey key : keys) {
            WALValue rowIndexValue = regionStripe.get(regionName, key);
            if (rowIndexValue != null && !rowIndexValue.getTombstoned()) {
                if (!valuesStream.row(-1, key, rowIndexValue)) {
                    return;
                }
            }
        }
    }

    public void scan(Scan<WALValue> stream) throws Exception {
        regionStripe.rowScan(regionName, stream);
    }

    public void rangeScan(WALKey from, WALKey to, Scan<WALValue> stream) throws Exception {
        regionStripe.rangeScan(regionName, from, to, stream);
    }

    public boolean remove(WALKey key) throws Exception {
        RowStoreUpdates tx = regionStripe.startTransaction(regionName);
        tx.put(key, new WALValue(null, orderIdProvider.nextId(), true));
        tx.commit(replicator);
        return true;
    }

    public void remove(Iterable<WALKey> keys) throws Exception {
        long timestamp = orderIdProvider.nextId();
        RowStoreUpdates tx = regionStripe.startTransaction(regionName);
        for (WALKey key : keys) {
            tx.put(key, new WALValue(null, timestamp, true));
        }
        tx.commit(replicator);
    }

    public void takeRowUpdatesSince(long transactionId, RowStream rowStream) throws Exception {
        regionStripe.takeRowUpdatesSince(regionName, transactionId, rowStream);
    }

    public boolean takeFromTransactionId(long transactionId, Scan<WALValue> scan) throws Exception {
        return regionStripe.takeFromTransactionId(regionName, transactionId, scan);
    }

    //  Use for testing
    public boolean compare(final AmzaRegion amzaRegion) throws Exception {
        final MutableInt compared = new MutableInt(0);
        final MutableBoolean passed = new MutableBoolean(true);
        amzaRegion.scan((long txid, WALKey key, WALValue value) -> {
            try {
                compared.increment();

                WALValue timestampedValue = regionStripe.get(regionName, key);
                String comparing = regionName.getRingName() + ":" + regionName.getRegionName()
                    + " to " + amzaRegion.regionName.getRingName() + ":" + amzaRegion.regionName.getRegionName() + "\n";

                if (timestampedValue == null) {
                    System.out.println("INCONSISTENCY: " + comparing + " key:null"
                        + " != " + value.getTimestampId()
                        + "' \n" + timestampedValue + " vs " + value);
                    passed.setValue(false);
                    return false;
                }
                if (value.getTimestampId() != timestampedValue.getTimestampId()) {
                    System.out.println("INCONSISTENCY: " + comparing + " timstamp:'" + timestampedValue.getTimestampId()
                        + "' != '" + value.getTimestampId()
                        + "' \n" + timestampedValue + " vs " + value);
                    passed.setValue(false);
                    System.out.println("----------------------------------");

                    return false;
                }
                if (value.getTombstoned() != timestampedValue.getTombstoned()) {
                    System.out.println("INCONSISTENCY: " + comparing + " tombstone:" + timestampedValue.getTombstoned()
                        + " != '" + value.getTombstoned()
                        + "' \n" + timestampedValue + " vs " + value);
                    passed.setValue(false);
                    return false;
                }
                if (value.getValue() == null && timestampedValue.getValue() != null) {
                    System.out.println("INCONSISTENCY: " + comparing + " null values:" + timestampedValue.getTombstoned()
                        + " != '" + value.getTombstoned()
                        + "' \n" + timestampedValue + " vs " + value);
                    passed.setValue(false);
                    return false;
                }
                if (value.getValue() != null && !Arrays.equals(value.getValue(), timestampedValue.getValue())) {
                    System.out.println("INCONSISTENCY: " + comparing + " value:'" + timestampedValue.getValue()
                        + "' != '" + value.getValue()
                        + "' aClass:" + timestampedValue.getValue().getClass()
                        + "' bClass:" + value.getValue().getClass()
                        + "' \n" + timestampedValue + " vs " + value);
                    passed.setValue(false);
                    return false;
                }
                return true;
            } catch (Exception x) {
                throw new RuntimeException("Failed while comparing", x);
            }
        });

        System.out.println("region:" + amzaRegion.regionName.getRegionName() + " compared:" + compared + " keys");
        return passed.booleanValue();
    }

    public long count() throws Exception {
        return regionStripe.count(regionName);
    }
}
