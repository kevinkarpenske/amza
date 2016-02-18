package com.jivesoftware.os.amza.service.replication;

import com.jivesoftware.os.amza.api.BAInterner;
import com.jivesoftware.os.amza.api.TimestampedValue;
import com.jivesoftware.os.amza.api.filer.UIO;
import com.jivesoftware.os.amza.api.partition.PartitionName;
import com.jivesoftware.os.amza.api.partition.PartitionStripeFunction;
import com.jivesoftware.os.amza.api.partition.RingMembership;
import com.jivesoftware.os.amza.api.partition.StorageVersion;
import com.jivesoftware.os.amza.api.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.api.ring.RingMember;
import com.jivesoftware.os.amza.api.scan.RowChanges;
import com.jivesoftware.os.amza.api.scan.RowsChanged;
import com.jivesoftware.os.amza.api.wal.WALKey;
import com.jivesoftware.os.amza.api.wal.WALUpdated;
import com.jivesoftware.os.amza.api.wal.WALValue;
import com.jivesoftware.os.amza.service.AwaitNotify;
import com.jivesoftware.os.amza.service.NotARingMemberException;
import com.jivesoftware.os.amza.service.PropertiesNotPresentException;
import com.jivesoftware.os.amza.service.partition.VersionedPartitionProvider;
import com.jivesoftware.os.amza.service.storage.PartitionCreator;
import com.jivesoftware.os.amza.service.storage.SystemWALStorage;
import com.jivesoftware.os.filer.io.StripingLocksProvider;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProvider;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 *
 */
public class StorageVersionProvider implements CurrentVersionProvider, RowChanges {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final BAInterner interner;
    private final OrderIdProvider orderIdProvider;
    private final RingMember rootRingMember;
    private final SystemWALStorage systemWALStorage;
    private final VersionedPartitionProvider versionedPartitionProvider;
    private final RingMembership ringMembership;
    private final PartitionStripeFunction partitionStripeFunction;
    private final long[] stripeVersions;
    private final WALUpdated walUpdated;
    private final AwaitNotify<PartitionName> awaitNotify;

    private final ConcurrentHashMap<PartitionName, StorageVersion> localVersionCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<RingMemberAndPartitionName, StorageVersion> remoteVersionCache = new ConcurrentHashMap<>();

    public StorageVersionProvider(BAInterner interner,
        OrderIdProvider orderIdProvider,
        RingMember rootRingMember,
        SystemWALStorage systemWALStorage,
        VersionedPartitionProvider versionedPartitionProvider,
        RingMembership ringMembership,
        PartitionStripeFunction partitionStripeFunction,
        long[] stripeVersions,
        WALUpdated walUpdated,
        AwaitNotify<PartitionName> awaitNotify) {
        this.interner = interner;
        this.orderIdProvider = orderIdProvider;
        this.rootRingMember = rootRingMember;
        this.systemWALStorage = systemWALStorage;
        this.versionedPartitionProvider = versionedPartitionProvider;
        this.ringMembership = ringMembership;
        this.partitionStripeFunction = partitionStripeFunction;
        this.stripeVersions = stripeVersions;
        this.walUpdated = walUpdated;
        this.awaitNotify = awaitNotify;
    }

    private static byte[] walKey(RingMember member, PartitionName partitionName) throws Exception {
        byte[] memberBytes = member.toBytes();
        if (partitionName != null) {
            byte[] partitionNameBytes = partitionName.toBytes();
            byte[] asBytes = new byte[1 + 4 + memberBytes.length + 4 + partitionNameBytes.length];
            asBytes[0] = 0; // version
            UIO.intBytes(memberBytes.length, asBytes, 1);
            System.arraycopy(memberBytes, 0, asBytes, 1 + 4, memberBytes.length);
            UIO.intBytes(partitionNameBytes.length, asBytes, 1 + 4 + memberBytes.length);
            System.arraycopy(partitionNameBytes, 0, asBytes, 1 + 4 + memberBytes.length + 4, partitionNameBytes.length);
            return asBytes;
        } else {
            byte[] asBytes = new byte[1 + 4 + memberBytes.length];
            asBytes[0] = 0; // version
            UIO.intBytes(memberBytes.length, asBytes, 1);
            System.arraycopy(memberBytes, 0, asBytes, 1 + 4, memberBytes.length);
            return asBytes;
        }
    }

    private final StripingLocksProvider<PartitionName> versionStripingLocks = new StripingLocksProvider<>(1024);

    public StorageVersion createIfAbsent(PartitionName partitionName) throws Exception {
        if (partitionName.isSystemPartition()) {
            return new StorageVersion(0, 0);
        }
        synchronized (versionStripingLocks.lock(partitionName, 0)) {
            StorageVersion storageVersion = lookupStorageVersion(partitionName);
            if (storageVersion == null || storageVersion.stripeVersion != stripeVersions[partitionStripeFunction.stripe(partitionName)]) {
                if (versionedPartitionProvider.getProperties(partitionName) == null) {
                    throw new PropertiesNotPresentException("Properties missing for " + partitionName);
                }
                if (!ringMembership.isMemberOfRing(partitionName.getRingName())) {
                    throw new NotARingMemberException("Not a member of ring for " + partitionName);
                }
                storageVersion = set(partitionName, orderIdProvider.nextId());
            }
            return storageVersion;
        }
    }

    @Override
    public boolean isCurrentVersion(VersionedPartitionName versionedPartitionName) {
        PartitionName partitionName = versionedPartitionName.getPartitionName();
        if (partitionName.isSystemPartition()) {
            return true;
        }
        StorageVersion storageVersion = lookupStorageVersion(partitionName);
        return storageVersion != null && storageVersion.partitionVersion == versionedPartitionName.getPartitionVersion();
    }

    private StorageVersion lookupStorageVersion(PartitionName partitionName) {
        return localVersionCache.computeIfAbsent(partitionName, key -> {
            try {
                TimestampedValue rawState = systemWALStorage.getTimestampedValue(PartitionCreator.PARTITION_VERSION_INDEX, null,
                    walKey(rootRingMember, partitionName));
                if (rawState != null) {
                    return StorageVersion.fromBytes(rawState.getValue());
                } else {
                    return null;
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to deserialize version", e);
            }
        });
    }

    @Override
    public void abandonVersion(VersionedPartitionName versionedPartitionName) throws Exception {
        PartitionName partitionName = versionedPartitionName.getPartitionName();
        synchronized (versionStripingLocks.lock(partitionName, 0)) {
            StorageVersion storageVersion = lookupStorageVersion(partitionName);
            if (storageVersion != null && storageVersion.partitionVersion <= versionedPartitionName.getPartitionVersion()) {
                storageVersion = set(partitionName, orderIdProvider.nextId());
            }
        }
    }

    public interface PartitionMemberStorageVersionStream {

        boolean stream(PartitionName partitionName, RingMember ringMember, StorageVersion storageVersion) throws Exception;
    }

    public void streamLocal(PartitionMemberStorageVersionStream stream) throws Exception {
        byte[] fromKey = walKey(rootRingMember, null);
        byte[] toKey = WALKey.prefixUpperExclusive(fromKey);
        byte[] intBuffer = new byte[4];

        systemWALStorage.rangeScan(PartitionCreator.PARTITION_VERSION_INDEX, null, fromKey, null, toKey,
            (rowType, prefix, key, value, valueTimestamp, valueTombstoned, valueVersion) -> {
                if (valueTimestamp != -1 && !valueTombstoned) {

                    int o = 0;
                    o++; //serializationVersion
                    int ringMemberLength = UIO.bytesInt(key, o);
                    o += 4;
                    RingMember ringMember = RingMember.fromBytes(key, o, ringMemberLength, interner);
                    o += ringMemberLength;
                    o += 4; // partitionNameLength
                    PartitionName partitionName = PartitionName.fromBytes(key, o, interner);
                    StorageVersion storageVersion = StorageVersion.fromBytes(value);

                    if (storageVersion.stripeVersion == stripeVersions[partitionStripeFunction.stripe(partitionName)]) {
                        return stream.stream(partitionName, ringMember, storageVersion);
                    }
                }
                return true;
            });
    }

    public static PartitionName fromKey(byte[] key, BAInterner interner) throws Exception {
        int o = 0;
        o++; //serializationVersion
        int ringMemberLength = UIO.bytesInt(key, o);
        o += 4;
        o += ringMemberLength;
        o += 4; // partitionNameLength
        return PartitionName.fromBytes(key, o, interner);
    }

    public StorageVersion getRemote(RingMember ringMember, PartitionName partitionName) throws Exception {
        return remoteVersionCache.computeIfAbsent(new RingMemberAndPartitionName(ringMember, partitionName), key -> {
            try {
                TimestampedValue rawState = systemWALStorage.getTimestampedValue(PartitionCreator.PARTITION_VERSION_INDEX, null,
                    walKey(ringMember, partitionName));
                if (rawState == null) {
                    return null;
                }
                return StorageVersion.fromBytes(rawState.getValue());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private StorageVersion set(PartitionName partitionName, long partitionVersion) throws Exception {
        StorageVersion storageVersion = new StorageVersion(partitionVersion, stripeVersions[partitionStripeFunction.stripe(partitionName)]);
        VersionedPartitionName versionedPartitionName = new VersionedPartitionName(partitionName, partitionVersion);
        StorageVersion cachedVersion = localVersionCache.get(partitionName);
        if (cachedVersion != null && cachedVersion.equals(storageVersion)) {
            return storageVersion;
        }

        byte[] versionedStateBytes = storageVersion.toBytes();
        awaitNotify.notifyChange(partitionName, () -> {
            long timestampAndVersion = orderIdProvider.nextId();
            RowsChanged rowsChanged = systemWALStorage.update(PartitionCreator.PARTITION_VERSION_INDEX, null,
                (highwaters, scan) -> scan.row(orderIdProvider.nextId(),
                    walKey(rootRingMember, partitionName),
                    versionedStateBytes, timestampAndVersion, false, timestampAndVersion),
                walUpdated);
            return !rowsChanged.isEmpty();
        });

        LOG.info("Storage version: {} {} was updated to {}", rootRingMember, versionedPartitionName, partitionVersion);
        localVersionCache.put(partitionName, storageVersion);
        //TODO anything to notify?
        //takeCoordinator.stateChanged(amzaRingReader, versionedPartitionName, commitableStorageVersion.state);
        //takeCoordinator.awakeCya();

        return storageVersion;
    }

    public boolean remove(RingMember rootRingMember, VersionedPartitionName versionedPartitionName) throws Exception {
        long timestampAndVersion = orderIdProvider.nextId();
        RowsChanged rowsChanged = systemWALStorage.update(PartitionCreator.PARTITION_VERSION_INDEX, null,
            (highwaters, scan) -> scan.row(orderIdProvider.nextId(),
                walKey(rootRingMember, versionedPartitionName.getPartitionName()),
                null,
                timestampAndVersion,
                true,
                timestampAndVersion),
            walUpdated);

        LOG.info("Storage version: {} {} was removed: {}", rootRingMember, versionedPartitionName, rowsChanged);
        invalidateLocalVersionCache(versionedPartitionName);
        return !rowsChanged.isEmpty();
    }

    @Override
    public void changes(RowsChanged changes) throws Exception {
        if (PartitionCreator.PARTITION_VERSION_INDEX.equals(changes.getVersionedPartitionName())) {
            for (Map.Entry<WALKey, WALValue> change : changes.getApply().entrySet()) {
                clearCache(change.getKey().key, change.getValue().getValue());
            }
        }
    }

    private void invalidateLocalVersionCache(VersionedPartitionName versionedPartitionName) {
        localVersionCache.computeIfPresent(versionedPartitionName.getPartitionName(), (partitionName, versionedState) -> {
            if (versionedState.partitionVersion == versionedPartitionName.getPartitionVersion()) {
                return null;
            } else {
                return versionedState;
            }
        });
    }

    private void invalidateRemoteVersionCache(RingMember ringMember, PartitionName partitionName) {
        remoteVersionCache.remove(new RingMemberAndPartitionName(ringMember, partitionName));
    }

    void clearCache(byte[] walKey, byte[] walValue) throws Exception {
        int o = 0;
        o++;// serializationVersion
        int ringMemberLength = UIO.bytesInt(walKey, o);
        o += 4;
        RingMember ringMember = RingMember.fromBytes(walKey, o, ringMemberLength, interner);
        o += ringMemberLength;
        if (ringMember != null) {
            o += 4; // partitionNameLength
            PartitionName partitionName = PartitionName.fromBytes(walKey, o, interner);
            if (ringMember.equals(rootRingMember)) {
                StorageVersion storageVersion = StorageVersion.fromBytes(walValue);
                invalidateLocalVersionCache(new VersionedPartitionName(partitionName, storageVersion.partitionVersion));
            } else {
                invalidateRemoteVersionCache(ringMember, partitionName);
            }
        }
    }

    private static class RingMemberAndPartitionName {

        private final byte[] ringMemberBytes;

        private final boolean systemPartition;
        private final byte[] ringNameBytes;
        private final byte[] partitionNameBytes;

        private final int hash;

        public RingMemberAndPartitionName(RingMember ringMember, PartitionName partitionName) {
            this.ringMemberBytes = ringMember.leakBytes();

            this.systemPartition = partitionName.isSystemPartition();
            this.ringNameBytes = partitionName.getRingName();
            this.partitionNameBytes = partitionName.getName();

            this.hash = ringMember.hashCode() + 31 * partitionName.hashCode();
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            RingMemberAndPartitionName that = (RingMemberAndPartitionName) o;

            if (systemPartition != that.systemPartition) {
                return false;
            }
            if (!Arrays.equals(ringMemberBytes, that.ringMemberBytes)) {
                return false;
            }
            if (!Arrays.equals(ringNameBytes, that.ringNameBytes)) {
                return false;
            }
            return Arrays.equals(partitionNameBytes, that.partitionNameBytes);

        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

}
