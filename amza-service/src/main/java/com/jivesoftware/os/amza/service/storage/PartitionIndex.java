package com.jivesoftware.os.amza.service.storage;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.jivesoftware.os.amza.api.TimestampedValue;
import com.jivesoftware.os.amza.api.partition.Consistency;
import com.jivesoftware.os.amza.api.partition.Durability;
import com.jivesoftware.os.amza.api.partition.PartitionName;
import com.jivesoftware.os.amza.api.partition.PartitionProperties;
import com.jivesoftware.os.amza.api.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.api.scan.RowChanges;
import com.jivesoftware.os.amza.api.scan.RowsChanged;
import com.jivesoftware.os.amza.api.stream.RowType;
import com.jivesoftware.os.amza.api.wal.WALKey;
import com.jivesoftware.os.amza.api.wal.WALValue;
import com.jivesoftware.os.amza.service.IndexedWALStorageProvider;
import com.jivesoftware.os.amza.service.partition.VersionedPartitionProvider;
import com.jivesoftware.os.amza.service.stats.AmzaStats;
import com.jivesoftware.os.filer.io.StripingLocksProvider;
import com.jivesoftware.os.jive.utils.ordered.id.TimestampedOrderIdProvider;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static com.jivesoftware.os.amza.service.storage.PartitionCreator.REGION_PROPERTIES;

/**
 * @author jonathan.colt
 */
public class PartitionIndex implements RowChanges, VersionedPartitionProvider {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private static final PartitionProperties REPLICATED_PROPERTIES = new PartitionProperties(Durability.fsync_never,
        0, 0, 0, 0, 0, 0, 0, 0,
        true,
        Consistency.none,
        true,
        2,
        false,
        RowType.primary,
        "memory_persistent",
        null);

    private static final PartitionProperties NON_REPLICATED_PROPERTIES = new PartitionProperties(Durability.fsync_never,
        0, 0, 0, 0, 0, 0, 0, 0,
        true,
        Consistency.none,
        true,
        0,
        false,
        RowType.primary,
        "memory_persistent",
        null);

    private static final PartitionProperties AQUARIUM_PROPERTIES = new PartitionProperties(Durability.ephemeral,
        0, 0, 0, 0, 0, 0, 0, 0,
        false,
        Consistency.none,
        true,
        2,
        false,
        RowType.primary,
        "memory_ephemeral",
        null);

    private final ConcurrentHashMap<PartitionName, ConcurrentHashMap<Long, PartitionStore>> partitionStores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PartitionName, PartitionProperties> partitionProperties = new ConcurrentHashMap<>();
    private final StripingLocksProvider<VersionedPartitionName> locksProvider = new StripingLocksProvider<>(1024); // TODO expose to config

    private final AmzaStats amzaStats;
    private final TimestampedOrderIdProvider orderIdProvider;
    private final IndexedWALStorageProvider walStorageProvider;
    private final PartitionPropertyMarshaller partitionPropertyMarshaller;

    public PartitionIndex(AmzaStats amzaStats,
        TimestampedOrderIdProvider orderIdProvider,
        IndexedWALStorageProvider walStorageProvider,
        PartitionPropertyMarshaller partitionPropertyMarshaller) {
        this.amzaStats = amzaStats;
        this.orderIdProvider = orderIdProvider;
        this.walStorageProvider = walStorageProvider;
        this.partitionPropertyMarshaller = partitionPropertyMarshaller;
    }

    private static final Map<VersionedPartitionName, PartitionProperties> SYSTEM_PARTITIONS = ImmutableMap.<VersionedPartitionName,
        PartitionProperties>builder()
        .put(PartitionCreator.REGION_INDEX, REPLICATED_PROPERTIES)
        .put(PartitionCreator.RING_INDEX, REPLICATED_PROPERTIES)
        .put(PartitionCreator.NODE_INDEX, REPLICATED_PROPERTIES)
        .put(PartitionCreator.PARTITION_VERSION_INDEX, REPLICATED_PROPERTIES)
        .put(PartitionCreator.REGION_PROPERTIES, REPLICATED_PROPERTIES)
        .put(PartitionCreator.HIGHWATER_MARK_INDEX, NON_REPLICATED_PROPERTIES)
        .put(PartitionCreator.AQUARIUM_STATE_INDEX, AQUARIUM_PROPERTIES)
        .put(PartitionCreator.AQUARIUM_LIVELINESS_INDEX, AQUARIUM_PROPERTIES)
        .build();

    public void open() throws Exception {
        for (VersionedPartitionName versionedPartitionName : SYSTEM_PARTITIONS.keySet()) {
            get(versionedPartitionName);
        }
    }

    @Override
    public PartitionProperties getProperties(PartitionName partitionName) {

        return partitionProperties.computeIfAbsent(partitionName, (key) -> {
            try {
                if (partitionName.isSystemPartition()) {
                    return SYSTEM_PARTITIONS.get(new VersionedPartitionName(partitionName, VersionedPartitionName.STATIC_VERSION));
                } else {
                    TimestampedValue rawPartitionProperties = getSystemPartition(PartitionCreator.REGION_PROPERTIES)
                        .getTimestampedValue(null, partitionName.toBytes());
                    if (rawPartitionProperties == null) {
                        return null;
                    }
                    return partitionPropertyMarshaller.fromBytes(rawPartitionProperties.getValue());
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    public PartitionStore getIfPresent(VersionedPartitionName versionedPartitionName) {
        ConcurrentHashMap<Long, PartitionStore> versionedStores = partitionStores.get(versionedPartitionName.getPartitionName());
        if (versionedStores != null) {
            return versionedStores.get(versionedPartitionName.getPartitionVersion());
        }
        return null;
    }

    public PartitionStore get(VersionedPartitionName versionedPartitionName) throws Exception {
        return getAndValidate(-1, -1, versionedPartitionName);
    }

    public PartitionStore getAndValidate(long deltaWALId, long prevDeltaWALId, VersionedPartitionName versionedPartitionName) throws Exception {
        PartitionName partitionName = versionedPartitionName.getPartitionName();
        if (deltaWALId > -1 && partitionName.isSystemPartition()) {
            throw new IllegalStateException("Hooray you have a bug! Should never call get with something other than -1 for system parititions." + deltaWALId);
        }
        ConcurrentHashMap<Long, PartitionStore> versionedStores = partitionStores.get(partitionName);
        if (versionedStores != null) {
            PartitionStore partitionStore = versionedStores.get(versionedPartitionName.getPartitionVersion());
            if (partitionStore != null) {
                partitionStore.load(deltaWALId, prevDeltaWALId);
                return partitionStore;
            }
        }

        if (!versionedPartitionName.getPartitionName().isSystemPartition()
            && !getSystemPartition(PartitionCreator.REGION_INDEX).containsKey(null, partitionName.toBytes())) {
            return null;
        }

        PartitionProperties properties = getProperties(partitionName);
        if (properties == null) {
            return null;
        }
        return open(deltaWALId, prevDeltaWALId, versionedPartitionName, properties);

    }

    private PartitionStore getSystemPartition(VersionedPartitionName versionedPartitionName) {
        Preconditions.checkArgument(versionedPartitionName.getPartitionName().isSystemPartition(), "Should only be called by system partitions.");
        ConcurrentHashMap<Long, PartitionStore> versionedPartitionStores = partitionStores.get(versionedPartitionName.getPartitionName());
        PartitionStore store = versionedPartitionStores == null ? null : versionedPartitionStores.get(0L);
        if (store == null) {
            throw new IllegalStateException("There is no system partition for " + versionedPartitionName);
        }
        return store;
    }

    public void delete(VersionedPartitionName versionedPartitionName) throws Exception {
        ConcurrentHashMap<Long, PartitionStore> versionedStores = partitionStores.get(versionedPartitionName.getPartitionName());
        if (versionedStores != null) {
            PartitionStore partitionStore = versionedStores.get(versionedPartitionName.getPartitionVersion());
            if (partitionStore != null) {
                partitionStore.delete();
                versionedStores.remove(versionedPartitionName.getPartitionVersion());
            }
        }
    }

    private PartitionStore open(long deltaWALId,
        long prevDeltaWALId,
        VersionedPartitionName versionedPartitionName,
        PartitionProperties properties) throws Exception {
        synchronized (locksProvider.lock(versionedPartitionName, 1234)) {
            ConcurrentHashMap<Long, PartitionStore> versionedStores = partitionStores.computeIfAbsent(versionedPartitionName.getPartitionName(),
                (key) -> new ConcurrentHashMap<>());

            PartitionStore partitionStore = versionedStores.get(versionedPartitionName.getPartitionVersion());
            if (partitionStore != null) {
                return partitionStore;
            }

            WALStorage<?> walStorage = walStorageProvider.create(versionedPartitionName, properties);
            partitionStore = new PartitionStore(amzaStats, orderIdProvider, versionedPartitionName, walStorage, properties);
            partitionStore.load(deltaWALId, prevDeltaWALId);

            versionedStores.put(versionedPartitionName.getPartitionVersion(), partitionStore);
            LOG.info("Opened partition:" + versionedPartitionName);

            return partitionStore;
        }
    }

    public void putProperties(PartitionName partitionName, PartitionProperties properties) {
        partitionProperties.put(partitionName, properties);
    }

    public void removeProperties(PartitionName partitionName) {
        partitionProperties.remove(partitionName);
    }

    public boolean exists(VersionedPartitionName versionedPartitionName) throws Exception {
        return get(versionedPartitionName) != null;
    }

    @Override
    public Iterable<PartitionName> getAllPartitions() throws Exception {
        PartitionStore propertiesStore = get(PartitionCreator.REGION_PROPERTIES);
        List<PartitionName> partitionNames = Lists.newArrayList();
        propertiesStore.rowScan((rowType, prefix, key, value, valueTimestamp, valueTombstoned, valueVersion) -> {
            if (!valueTombstoned && valueTimestamp != -1) {
                partitionNames.add(PartitionName.fromBytes(key));
            }
            return true;
        });
        return partitionNames;
    }

    @Override
    public Iterable<VersionedPartitionName> getMemberPartitions() {
        return Iterables.concat(Iterables.transform(partitionStores.entrySet(),
            (partitionVersions) -> Iterables.transform(partitionVersions.getValue().keySet(),
                (partitionVersion) -> new VersionedPartitionName(partitionVersions.getKey(), partitionVersion))));
    }

    public Iterable<VersionedPartitionName> getSystemPartitions() {
        return SYSTEM_PARTITIONS.keySet();
    }

    // TODO this is never called
    @Override
    public void changes(final RowsChanged changes) throws Exception {
        if (changes.getVersionedPartitionName().getPartitionName().equals(REGION_PROPERTIES.getPartitionName())) {
            try {
                for (Map.Entry<WALKey, WALValue> entry : changes.getApply().entrySet()) {
                    PartitionName partitionName = PartitionName.fromBytes(entry.getKey().key);
                    removeProperties(partitionName);

                    ConcurrentHashMap<Long, PartitionStore> versionedPartitionStores = partitionStores.get(partitionName);
                    if (versionedPartitionStores != null) {
                        for (PartitionStore store : versionedPartitionStores.values()) {
                            PartitionProperties properties = getProperties(partitionName);
                            store.updateProperties(properties);
                        }
                    }
                }
            } catch (Throwable ex) {
                throw new RuntimeException("Error while streaming entry set.", ex);
            }
        }
    }

    public void invalidate(PartitionName partitionName) {
        partitionStores.remove(partitionName);
        partitionProperties.remove(partitionName);
    }
}
