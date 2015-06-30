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

import com.jivesoftware.os.amza.service.storage.SystemWALStorage;
import com.jivesoftware.os.amza.shared.AckWaters;
import com.jivesoftware.os.amza.shared.AmzaPartitionAPI;
import com.jivesoftware.os.amza.shared.FailedToAchieveQuorumException;
import com.jivesoftware.os.amza.shared.partition.PartitionName;
import com.jivesoftware.os.amza.shared.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.shared.ring.AmzaRingReader;
import com.jivesoftware.os.amza.shared.ring.RingMember;
import com.jivesoftware.os.amza.shared.scan.Commitable;
import com.jivesoftware.os.amza.shared.scan.RowsChanged;
import com.jivesoftware.os.amza.shared.scan.Scan;
import com.jivesoftware.os.amza.shared.stats.AmzaStats;
import com.jivesoftware.os.amza.shared.take.HighwaterStorage;
import com.jivesoftware.os.amza.shared.take.Highwaters;
import com.jivesoftware.os.amza.shared.take.TakeResult;
import com.jivesoftware.os.amza.shared.wal.WALHighwater;
import com.jivesoftware.os.amza.shared.wal.WALKey;
import com.jivesoftware.os.amza.shared.wal.WALUpdated;
import com.jivesoftware.os.amza.shared.wal.WALValue;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProvider;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.util.Set;
import org.apache.commons.lang.mutable.MutableLong;

public class SystemPartition implements AmzaPartitionAPI {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final AmzaStats amzaStats;
    private final OrderIdProvider orderIdProvider;
    private final WALUpdated walUpdated;
    private final RingMember ringMember;
    private final VersionedPartitionName versionedPartitionName;
    private final SystemWALStorage systemWALStorage;
    private final HighwaterStorage systemHighwaterStorage;
    private final AckWaters ackWaters;
    private final AmzaRingStoreReader ringReader;

    public SystemPartition(AmzaStats amzaStats,
        OrderIdProvider orderIdProvider,
        WALUpdated walUpdated,
        RingMember ringMember,
        PartitionName partitionName,
        SystemWALStorage systemWALStorage,
        HighwaterStorage systemHighwaterStorage,
        AckWaters ackWaters,
        AmzaRingStoreReader ringReader) {

        this.amzaStats = amzaStats;
        this.orderIdProvider = orderIdProvider;
        this.walUpdated = walUpdated;
        this.ringMember = ringMember;
        this.versionedPartitionName = new VersionedPartitionName(partitionName, 0);
        this.systemWALStorage = systemWALStorage;
        this.systemHighwaterStorage = systemHighwaterStorage;
        this.ackWaters = ackWaters;
        this.ringReader = ringReader;
    }

    public PartitionName getPartitionName() {
        return versionedPartitionName.getPartitionName();
    }

    @Override
    public void commit(Commitable<WALValue> updates,
        int takeQuorum,
        long timeoutInMillis) throws Exception {

        long timestampId = orderIdProvider.nextId();
        RowsChanged commit = systemWALStorage.update(versionedPartitionName, (highwaters, scan) -> {
            updates.commitable(highwaters, (rowTxId, key, scanned) -> {
                WALValue value = scanned.getTimestampId() > 0 ? scanned : new WALValue(scanned.getValue(), timestampId, scanned.getTombstoned());
                return scan.row(rowTxId, key, value);
            });
        }, walUpdated);

        Set<RingMember> ringMembers = ringReader.getNeighboringRingMembers(AmzaRingReader.SYSTEM_RING);

        if (takeQuorum > 0) {
            if (ringMembers.size() < takeQuorum) {
                throw new FailedToAchieveQuorumException("There are an insufficent number of nodes to achieve desired take quorum:" + takeQuorum);
            } else {
                LOG.debug("Awaiting quorum for {} ms", timeoutInMillis);
                int takenBy = ackWaters.await(versionedPartitionName, commit.getLargestCommittedTxId(), ringMembers, takeQuorum, timeoutInMillis);
                if (takenBy < takeQuorum) {
                    throw new FailedToAchieveQuorumException("Timed out attempting to achieve desired take quorum:" + takeQuorum + " got:" + takenBy);
                }
            }
        }
    }

    @Override
    public void get(Iterable<WALKey> keys, Scan<TimestampedValue> valuesStream) throws Exception {
        for (WALKey walKey : keys) {
            WALValue got = systemWALStorage.get(versionedPartitionName, walKey); // TODO Hmmm add a multi get?
            valuesStream.row(-1, walKey, got == null || got.getTombstoned() ? null : got.toTimestampedValue());
        }
    }

    @Override
    public void scan(WALKey from, WALKey to, Scan<TimestampedValue> scan) throws Exception {
        if (from == null && to == null) {
            systemWALStorage.rowScan(versionedPartitionName,
                (rowTxId, key, scanned) -> scanned.getTombstoned() || scan.row(rowTxId, key, scanned.toTimestampedValue()));
        } else {
            systemWALStorage.rangeScan(versionedPartitionName,
                from == null ? new WALKey(new byte[0]) : from,
                to,
                (rowTxId, key, scanned) -> scanned.getTombstoned() || scan.row(rowTxId, key, scanned.toTimestampedValue()));
        }
    }

    @Override
    public TakeResult takeFromTransactionId(long transactionId, Highwaters highwaters, Scan<TimestampedValue> scan) throws Exception {
        final MutableLong lastTxId = new MutableLong(-1);
        WALHighwater partitionHighwater = systemHighwaterStorage.getPartitionHighwater(versionedPartitionName);
        boolean tookToEnd = systemWALStorage.takeFromTransactionId(versionedPartitionName, transactionId, highwaters, (rowTxId, key, value) -> {
            if (value.getTombstoned() || scan.row(rowTxId, key, value.toTimestampedValue())) {
                if (rowTxId > lastTxId.longValue()) {
                    lastTxId.setValue(rowTxId);
                }
                return true;
            }
            return false;
        });
        return new TakeResult(ringMember, lastTxId.longValue(), tookToEnd ? partitionHighwater : null);
    }

    public long count() throws Exception {
        return systemWALStorage.count(versionedPartitionName);
    }

}
