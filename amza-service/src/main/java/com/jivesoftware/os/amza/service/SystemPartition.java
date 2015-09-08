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

import com.google.common.base.Preconditions;
import com.jivesoftware.os.amza.api.Consistency;
import com.jivesoftware.os.amza.api.partition.HighestPartitionTx;
import com.jivesoftware.os.amza.api.partition.PartitionName;
import com.jivesoftware.os.amza.api.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.api.ring.RingMember;
import com.jivesoftware.os.amza.api.stream.Commitable;
import com.jivesoftware.os.amza.api.stream.KeyValueTimestampStream;
import com.jivesoftware.os.amza.api.stream.TxKeyValueStream;
import com.jivesoftware.os.amza.api.stream.UnprefixedWALKeys;
import com.jivesoftware.os.amza.api.take.Highwaters;
import com.jivesoftware.os.amza.api.take.TakeResult;
import com.jivesoftware.os.amza.api.wal.WALHighwater;
import com.jivesoftware.os.amza.service.storage.SystemWALStorage;
import com.jivesoftware.os.amza.shared.AckWaters;
import com.jivesoftware.os.amza.shared.FailedToAchieveQuorumException;
import com.jivesoftware.os.amza.shared.Partition;
import com.jivesoftware.os.amza.shared.ring.AmzaRingReader;
import com.jivesoftware.os.amza.shared.scan.RowsChanged;
import com.jivesoftware.os.amza.shared.stats.AmzaStats;
import com.jivesoftware.os.amza.shared.stream.KeyValueStream;
import com.jivesoftware.os.amza.shared.take.HighwaterStorage;
import com.jivesoftware.os.amza.shared.wal.WALUpdated;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProvider;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.util.Set;

public class SystemPartition implements Partition {

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
    public void commit(Consistency consistency,
        byte[] prefix,
        Commitable updates,
        long timeoutInMillis) throws Exception {
        long currentTime = System.currentTimeMillis();
        long version = orderIdProvider.nextId();
        RowsChanged commit = systemWALStorage.update(versionedPartitionName, prefix,
            (highwaters, scan)
            -> updates.commitable(highwaters, (rowTxId, key, value, valueTimestamp, valueTombstone, valueVersion) -> {
                long timestamp = valueTimestamp > 0 ? valueTimestamp : currentTime;
                return scan.row(rowTxId, key, value, timestamp, valueTombstone, version);
            }),
            walUpdated);
        amzaStats.direct(versionedPartitionName.getPartitionName(), commit.getApply().size(), commit.getOldestRowTxId());

        Set<RingMember> ringMembers = ringReader.getNeighboringRingMembers(AmzaRingReader.SYSTEM_RING);

        int takeQuorum = consistency.quorum(ringMembers.size());
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
    public boolean get(Consistency consistency, byte[] prefix, UnprefixedWALKeys keys, KeyValueStream stream) throws Exception {
        return systemWALStorage.get(versionedPartitionName, prefix, keys, stream);
    }

    @Override
    public boolean scan(Consistency consistency,
        byte[] fromPrefix,
        byte[] fromKey,
        byte[] toPrefix,
        byte[] toKey,
        KeyValueTimestampStream scan) throws Exception {
        if (fromKey == null && toKey == null) {
            return systemWALStorage.rowScan(versionedPartitionName, (prefix, key, value, valueTimestamp, valueTombstone, valueVersion)
                -> valueTombstone || scan.stream(prefix, key, value, valueTimestamp, valueVersion));
        } else {
            return systemWALStorage.rangeScan(versionedPartitionName,
                fromPrefix,
                fromKey,
                toPrefix,
                toKey,
                (prefix, key, value, valueTimestamp, valueTombstone, valueVersion)
                -> valueTombstone || scan.stream(prefix, key, value, valueTimestamp, valueVersion));
        }
    }

    @Override
    public TakeResult takeFromTransactionId(Consistency consistency,
        long txId,
        Highwaters highwaters,
        TxKeyValueStream stream) throws Exception {
        return takeFromTransactionIdInternal(false, null, txId, highwaters, stream);
    }

    @Override
    public TakeResult takePrefixFromTransactionId(Consistency consistency,
        byte[] prefix,
        long txId,
        Highwaters highwaters,
        TxKeyValueStream stream) throws Exception {

        Preconditions.checkNotNull(prefix, "Must specify a prefix");
        return takeFromTransactionIdInternal(true, prefix, txId, highwaters, stream);
    }

    private TakeResult takeFromTransactionIdInternal(boolean usePrefix, byte[] takePrefix,
        long txId,
        Highwaters highwaters,
        TxKeyValueStream stream) throws Exception {

        long[] lastTxId = {-1};
        boolean[] done = {false};
        WALHighwater partitionHighwater = systemHighwaterStorage.getPartitionHighwater(versionedPartitionName);
        TxKeyValueStream delegateStream = (rowTxId, prefix, key, value, valueTimestamp, valueTombstone, valueVersion) -> {

            if (done[0] && rowTxId > lastTxId[0]) {
                return false;
            }

            done[0] |= !stream.stream(rowTxId, prefix, key, value, valueTimestamp, valueTombstone, valueVersion);
            if (rowTxId > lastTxId[0]) {
                lastTxId[0] = rowTxId;
            }
            return true;
        };
        boolean tookToEnd;
        if (usePrefix) {
            tookToEnd = systemWALStorage.takeFromTransactionId(versionedPartitionName, takePrefix, txId, highwaters, delegateStream);
        } else {
            tookToEnd = systemWALStorage.takeFromTransactionId(versionedPartitionName, txId, highwaters, delegateStream);
        }
        return new TakeResult(ringMember, lastTxId[0], tookToEnd ? partitionHighwater : null);
    }

    @Override
    public long count() throws Exception {
        return systemWALStorage.count(versionedPartitionName);
    }

    @Override
    public <R> R highestTxId(HighestPartitionTx<R> highestPartitionTx) throws Exception {
        return systemWALStorage.highestPartitionTxId(versionedPartitionName, highestPartitionTx);
    }

}
