package com.jivesoftware.os.amza.shared.wal;

import com.jivesoftware.os.amza.shared.partition.VersionedPartitionName;

/**
 *
 * @author jonathan.colt
 */
public class NoOpWALIndexProvider implements WALIndexProvider<NoOpWALIndex> {

    @Override
    public NoOpWALIndex createIndex(VersionedPartitionName versionedPartitionName) throws Exception {
        return new NoOpWALIndex();
    }

    @Override
    public void deleteIndex(VersionedPartitionName versionedPartitionName) throws Exception {
    }

}
