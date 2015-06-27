package com.jivesoftware.os.amza.service.replication;

import com.google.common.base.Optional;
import com.jivesoftware.os.amza.shared.partition.PartitionName;
import com.jivesoftware.os.amza.shared.partition.TxPartitionStatus;
import com.jivesoftware.os.amza.shared.wal.WALUpdated;

/**
 * @author jonathan.colt
 */
class StripedPartitionCommitChanges implements CommitChanges {

    private final PartitionName partitionName;
    private final PartitionStripeProvider partitionStripeProvider;
    private final boolean hardFlush;
    private final WALUpdated walUpdated;

    public StripedPartitionCommitChanges(PartitionName partitionName,
        PartitionStripeProvider partitionStripeProvider,
        boolean hardFlush,
        WALUpdated walUpdated) {

        this.partitionName = partitionName;
        this.partitionStripeProvider = partitionStripeProvider;
        this.hardFlush = hardFlush;
        this.walUpdated = walUpdated;
    }

    @Override
    public void commit(CommitTx commitTx) throws Exception {
        partitionStripeProvider.txPartition(partitionName,
            (stripe, highwaterStorage) -> {
                stripe.txPartition(partitionName,
                    (versionedPartitionName, partitionStatus) -> {
                        if (partitionStatus == TxPartitionStatus.Status.KETCHUP || partitionStatus == TxPartitionStatus.Status.ONLINE) {
                            commitTx.tx(versionedPartitionName, highwaterStorage,
                                (commitable) -> stripe.commit(highwaterStorage,
                                    partitionName,
                                    Optional.of(versionedPartitionName.getPartitionVersion()),
                                    false,
                                    commitable,
                                    walUpdated));
                        }
                        return false;
                    });
                return null;
            });
        partitionStripeProvider.flush(partitionName, hardFlush);
    }

    @Override
    public String toString() {
        return "StripedPartitionCommitChanges{" + "partitionName=" + partitionName + ", partitionStripeProvider=" + partitionStripeProvider + '}';
    }

}
