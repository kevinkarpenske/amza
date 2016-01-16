package com.jivesoftware.os.amza.api.partition;

/**
 *
 */
public class PartitionStripeFunction {

    private final int numberOfStripes;

    public PartitionStripeFunction(int numberOfStripes) {
        this.numberOfStripes = numberOfStripes;
    }

    public int stripe(PartitionName partitionName) {
        return Math.abs(partitionName.hashCode() % numberOfStripes);
    }

    public int getNumberOfStripes() {
        return numberOfStripes;
    }
}