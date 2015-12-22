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
package com.jivesoftware.os.amza.test;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.jivesoftware.os.amza.api.Consistency;
import com.jivesoftware.os.amza.api.partition.PartitionName;
import com.jivesoftware.os.amza.api.partition.PartitionProperties;
import com.jivesoftware.os.amza.api.partition.PrimaryIndexDescriptor;
import com.jivesoftware.os.amza.api.partition.VersionedPartitionName;
import com.jivesoftware.os.amza.api.partition.WALStorageDescriptor;
import com.jivesoftware.os.amza.api.ring.RingHost;
import com.jivesoftware.os.amza.api.ring.RingMember;
import com.jivesoftware.os.amza.api.ring.TimestampedRingHost;
import com.jivesoftware.os.amza.service.AmzaService;
import com.jivesoftware.os.amza.service.AmzaServiceInitializer.AmzaServiceConfig;
import com.jivesoftware.os.amza.service.EmbeddedAmzaServiceInitializer;
import com.jivesoftware.os.amza.service.replication.TakeFailureListener;
import com.jivesoftware.os.amza.service.storage.PartitionCreator;
import com.jivesoftware.os.amza.service.storage.PartitionPropertyMarshaller;
import com.jivesoftware.os.amza.shared.AmzaPartitionUpdates;
import com.jivesoftware.os.amza.shared.EmbeddedClientProvider;
import com.jivesoftware.os.amza.shared.Partition;
import com.jivesoftware.os.amza.shared.ring.AmzaRingReader;
import com.jivesoftware.os.amza.shared.ring.RingTopology;
import com.jivesoftware.os.amza.shared.scan.RowStream;
import com.jivesoftware.os.amza.shared.scan.RowsChanged;
import com.jivesoftware.os.amza.shared.stats.AmzaStats;
import com.jivesoftware.os.amza.shared.take.AvailableRowsTaker;
import com.jivesoftware.os.amza.shared.take.RowsTaker;
import com.jivesoftware.os.amza.shared.take.StreamingTakesConsumer;
import com.jivesoftware.os.amza.shared.take.StreamingTakesConsumer.StreamingTakeConsumed;
import com.jivesoftware.os.jive.utils.ordered.id.ConstantWriterIdProvider;
import com.jivesoftware.os.jive.utils.ordered.id.JiveEpochTimestampProvider;
import com.jivesoftware.os.jive.utils.ordered.id.OrderIdProviderImpl;
import com.jivesoftware.os.jive.utils.ordered.id.SnowflakeIdPacker;
import com.jivesoftware.os.jive.utils.ordered.id.TimestampedOrderIdProvider;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang.mutable.MutableBoolean;
import org.apache.commons.lang.mutable.MutableInt;
import org.xerial.snappy.SnappyInputStream;
import org.xerial.snappy.SnappyOutputStream;

public class AmzaTestCluster {

    private final MetricLogger LOG = MetricLoggerFactory.getLogger();

    private final File workingDirctory;
    private final ConcurrentSkipListMap<RingMember, AmzaNode> cluster = new ConcurrentSkipListMap<>();
    private int oddsOfAConnectionFailureWhenAdding = 0; // 0 never - 100 always
    private int oddsOfAConnectionFailureWhenTaking = 0; // 0 never - 100 always
    private AmzaService lastAmzaService = null;

    public AmzaTestCluster(File workingDirctory,
        int oddsOfAConnectionFailureWhenAdding,
        int oddsOfAConnectionFailureWhenTaking) {
        this.workingDirctory = workingDirctory;
        this.oddsOfAConnectionFailureWhenAdding = oddsOfAConnectionFailureWhenAdding;
        this.oddsOfAConnectionFailureWhenTaking = oddsOfAConnectionFailureWhenTaking;
    }

    /*
    class AmzaServiceRemotePartitionCaller implements RemotePartitionCaller<AmzaService, Exception> {

        @Override
        public PartitionResponse<NoOpCloseable> commit(RingMember leader, RingMember ringMember, AmzaService client, Consistency consistency, byte[] prefix,
            ClientUpdates updates, long abandonSolutionAfterNMillis) throws Exception {

        }

        @Override
        public PartitionResponse<CloseableStreamResponse> get(RingMember leader, RingMember ringMember, AmzaService client, Consistency consistency,
            byte[] prefix, UnprefixedWALKeys keys) throws Exception {

        }

        @Override
        public PartitionResponse<CloseableStreamResponse> scan(RingMember leader, RingMember ringMember, AmzaService client, Consistency consistency,
            byte[] fromPrefix, byte[] fromKey, byte[] toPrefix, byte[] toKey) throws Exception {

        }

        @Override
        public PartitionResponse<CloseableStreamResponse> takeFromTransactionId(RingMember leader, RingMember ringMember, AmzaService client,
            Map<RingMember, Long> membersTxId, TxKeyValueStream stream) throws Exception {

        }

        @Override
        public PartitionResponse<CloseableStreamResponse> takePrefixFromTransactionId(RingMember leader, RingMember ringMember, AmzaService client,
            byte[] prefix, Map<RingMember, Long> membersTxId, TxKeyValueStream stream) throws Exception {

        }

    }

    public AmzaClientProvider getClientProvider(RingMember ringMember) {

        PartitionHostsProvider partitionHostsProvider = new PartitionHostsProvider() {
            @Override
            public void ensurePartition(PartitionName partitionName, int desiredRingSize, PartitionProperties partitionProperties) throws Exception {

            }

            @Override
            public Ring getPartitionHosts(PartitionName partitionName, java.util.Optional<RingMemberAndHost> useHost, long waitForLeaderElection) throws
                Exception {

            }
        };
        RingHostClientProvider<AmzaService, Exception> clientProvider = new RingHostClientProvider<AmzaService, Exception>() {
            @Override
            public <R> R call(PartitionName partitionName, RingMember leader, RingMemberAndHost ringMemberAndHost, String family,
                PartitionCall<AmzaService, R, Exception> clientCall) throws Exception {

                return
            }
        };
        ExecutorService callerThreads = Executors.newCachedThreadPool();

        PartitionClientFactory<AmzaService, Exception> partitionClientFactory =
            (partitionName, partitionCallRouter, awaitLeaderElectionForNMillis) -> {
                return new AmzaPartitionClient(partitionName, partitionCallRouter, new AmzaServiceRemotePartitionCaller(), awaitLeaderElectionForNMillis);
            };
        long awaitLeaderElectionForNMillis = 30_000;
        return new AmzaClientProvider(partitionClientFactory, partitionHostsProvider, clientProvider, callerThreads, awaitLeaderElectionForNMillis);
    }*/

    public Collection<AmzaNode> getAllNodes() {
        return cluster.values();
    }

    public AmzaNode get(RingMember ringMember) {
        return cluster.get(ringMember);
    }

    public void remove(RingMember ringMember) {
        cluster.remove(ringMember);
    }

    public AmzaNode newNode(final RingMember localRingMember, final RingHost localRingHost, PartitionName partitionName) throws Exception {

        AmzaNode service = cluster.get(localRingMember);
        if (service != null) {
            return service;
        }

        AmzaServiceConfig config = new AmzaServiceConfig();
        config.workingDirectories = new String[]{workingDirctory.getAbsolutePath() + "/" + localRingHost.getHost() + "-" + localRingHost.getPort()};
        config.compactTombstoneIfOlderThanNMillis = 100000L;
        config.aquariumLivelinessFeedEveryMillis = 500;
        //config.useMemMap = true;
        SnowflakeIdPacker idPacker = new SnowflakeIdPacker();
        OrderIdProviderImpl orderIdProvider = new OrderIdProviderImpl(new ConstantWriterIdProvider(localRingHost.getPort()), idPacker,
            new JiveEpochTimestampProvider());

        AvailableRowsTaker availableRowsTaker =
            (localRingMember1, localTimestampedRingHost, remoteRingMember, remoteRingHost, takeSessionId, timeoutMillis, updatedPartitionsStream) -> {

                AmzaNode amzaNode = cluster.get(remoteRingMember);
                if (amzaNode == null) {
                    throw new IllegalStateException("Service doesn't exists for " + remoteRingMember);
                } else {
                    amzaNode.takePartitionUpdates(localRingMember1,
                        localTimestampedRingHost,
                        takeSessionId,
                        timeoutMillis,
                        (versionedPartitionName, txId) -> {
                            if (versionedPartitionName != null) {
                                updatedPartitionsStream.available(versionedPartitionName, txId);
                            }
                        },
                        () -> {
                            LOG.debug("Special delivery! Special delivery!");
                            return null;
                        },
                        () -> {
                            LOG.debug("Ping pong!");
                            return null;
                        });
                }
            };

        RowsTaker updateTaker = new RowsTaker() {

            @Override
            public RowsTaker.StreamingRowsResult rowsStream(RingMember localRingMember,
                RingMember remoteRingMember,
                RingHost remoteRingHost,
                VersionedPartitionName remoteVersionedPartitionName,
                long remoteTxId,
                long localLeadershipToken,
                RowStream rowStream) {

                AmzaNode amzaNode = cluster.get(remoteRingMember);
                if (amzaNode == null) {
                    throw new IllegalStateException("Service doesn't exist for " + localRingMember);
                } else {
                    StreamingTakesConsumer.StreamingTakeConsumed consumed = amzaNode.rowsStream(localRingMember,
                        remoteVersionedPartitionName,
                        remoteTxId,
                        localLeadershipToken,
                        rowStream);
                    HashMap<RingMember, Long> otherHighwaterMarks = consumed.isOnline ? new HashMap<>() : null;
                    return new StreamingRowsResult(null, null, consumed.leadershipToken, consumed.partitionVersion, otherHighwaterMarks);
                }
            }

            @Override
            public boolean rowsTaken(RingMember localRingMember,
                RingMember remoteRingMember,
                RingHost remoteRingHost,
                long takeSessionId,
                VersionedPartitionName remoteVersionedPartitionName,
                long remoteTxId,
                long localLeadershipToken) {
                AmzaNode amzaNode = cluster.get(remoteRingMember);
                if (amzaNode == null) {
                    throw new IllegalStateException("Service doesn't exists for " + localRingMember);
                } else {
                    try {
                        amzaNode.remoteMemberTookToTxId(localRingMember, takeSessionId, remoteVersionedPartitionName, remoteTxId, localLeadershipToken);
                        return true;
                    } catch (Exception x) {
                        throw new RuntimeException("Issue while applying acks.", x);
                    }
                }
            }
        };

        final ObjectMapper mapper = new ObjectMapper();
        PartitionPropertyMarshaller partitionPropertyMarshaller = new PartitionPropertyMarshaller() {

            @Override
            public PartitionProperties fromBytes(byte[] bytes) {
                try {
                    return mapper.readValue(bytes, PartitionProperties.class);
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }
            }

            @Override
            public byte[] toBytes(PartitionProperties partitionProperties) {
                try {
                    return mapper.writeValueAsBytes(partitionProperties);
                } catch (JsonProcessingException ex) {
                    throw new RuntimeException(ex);
                }
            }
        };

        AmzaStats amzaStats = new AmzaStats();
        Optional<TakeFailureListener> absent = Optional.<TakeFailureListener>absent();

        AmzaService amzaService = new EmbeddedAmzaServiceInitializer().initialize(config,
            amzaStats,
            localRingMember,
            localRingHost,
            orderIdProvider,
            idPacker,
            partitionPropertyMarshaller,
            (indexProviderRegistry, ephemeralRowIOProvider, persistentRowIOProvider) -> {
            },
            availableRowsTaker, () -> {
                return updateTaker;
            },
            absent,
            (RowsChanged changes) -> {
            });

        amzaService.start();

        amzaService.watch(partitionName,
            (RowsChanged changes) -> {
                /*if (changes.getApply().size() > 0) {
                 System.out.println("Service:" + localRingMember
                 + " Partition:" + partitionName.getName()
                 + " Changed:" + changes.getApply().size());
                 }*/
            }
        );

        try {
            //amzaService.getRingWriter().addRingMember(AmzaRingReader.SYSTEM_RING, localRingMember); // ?? Hacky
            TimestampedRingHost timestampedRingHost = amzaService.getRingReader().getRingHost();
            amzaService.getRingWriter().addRingMember("test".getBytes(), localRingMember); // ?? Hacky
            if (lastAmzaService != null) {
                TimestampedRingHost lastTimestampedRingHost = lastAmzaService.getRingReader().getRingHost();
                amzaService.getRingWriter().register(lastAmzaService.getRingReader().getRingMember(),
                    lastTimestampedRingHost.ringHost,
                    lastTimestampedRingHost.timestampId);
                amzaService.getRingWriter().addRingMember("test".getBytes(), lastAmzaService.getRingReader().getRingMember()); // ?? Hacky

                lastAmzaService.getRingWriter().register(localRingMember, localRingHost, timestampedRingHost.timestampId);
                lastAmzaService.getRingWriter().addRingMember("test".getBytes(), localRingMember); // ?? Hacky
            }
            lastAmzaService = amzaService;
        } catch (Exception x) {
            x.printStackTrace();
            System.out.println("FAILED CONNECTING RING");
            System.exit(1);
        }

        service = new AmzaNode(localRingMember, localRingHost, amzaService, orderIdProvider);

        cluster.put(localRingMember, service);

        System.out.println("Added serviceHost:" + localRingMember + " to the cluster.");
        return service;
    }

    public class AmzaNode {

        private final Random random = new Random();
        private final RingMember ringMember;
        private final RingHost ringHost;
        private final AmzaService amzaService;
        private final TimestampedOrderIdProvider orderIdProvider;
        private boolean off = false;
        private int flapped = 0;
        private final ExecutorService asIfOverTheWire = Executors.newSingleThreadExecutor();
        private final EmbeddedClientProvider clientProvider;

        public AmzaNode(RingMember ringMember,
            RingHost ringHost,
            AmzaService amzaService,
            TimestampedOrderIdProvider orderIdProvider) {

            this.ringMember = ringMember;
            this.ringHost = ringHost;
            this.amzaService = amzaService;
            this.clientProvider = new EmbeddedClientProvider(amzaService);
            this.orderIdProvider = orderIdProvider;
        }

        @Override
        public String toString() {
            return ringMember.toString();
        }

        public boolean isOff() {
            return off;
        }

        public void setOff(boolean off) {
            this.off = off;
            flapped++;
        }

        public void stop() throws Exception {
            amzaService.stop();
        }

        public void create(PartitionName partitionName) throws Exception {
            WALStorageDescriptor storageDescriptor = new WALStorageDescriptor(false,
                new PrimaryIndexDescriptor("memory_persistent", 0, false, null), null, 1000, 1000);

            // TODO test other consistencies. Hehe
            amzaService.setPropertiesIfAbsent(partitionName, new PartitionProperties(storageDescriptor, Consistency.none, true, 2, false));
            amzaService.awaitOnline(partitionName, 10_000);
        }

        public void update(PartitionName partitionName, byte[] p, byte[] k, byte[] v, boolean tombstone) throws Exception {
            if (off) {
                throw new RuntimeException("Service is off:" + ringMember);
            }

            AmzaPartitionUpdates updates = new AmzaPartitionUpdates();
            long timestamp = orderIdProvider.nextId();
            if (tombstone) {
                updates.remove(k, timestamp);
            } else {
                updates.set(k, v, timestamp);
            }
            clientProvider.getClient(partitionName).commit(Consistency.quorum, p, updates, 10, TimeUnit.SECONDS);

        }

        public byte[] get(PartitionName partitionName, byte[] prefix, byte[] key) throws Exception {
            if (off) {
                throw new RuntimeException("Service is off:" + ringMember);
            }

            List<byte[]> got = new ArrayList<>();
            clientProvider.getClient(partitionName).get(Consistency.none, prefix, stream -> stream.stream(key),
                (_prefix, _key, value, timestamp, version) -> {
                    got.add(value);
                    return true;
                });
            return got.get(0);
        }

        void remoteMemberTookToTxId(RingMember remoteRingMember,
            long takeSessionId,
            VersionedPartitionName remoteVersionedPartitionName,
            long localTxId,
            long leadershipToken) throws Exception {
            amzaService.rowsTaken(remoteRingMember, takeSessionId, remoteVersionedPartitionName, localTxId, leadershipToken);
        }

        public StreamingTakeConsumed rowsStream(RingMember remoteRingMember,
            VersionedPartitionName localVersionedPartitionName,
            long localTxId,
            long leadershipToken,
            RowStream rowStream) {
            if (off) {
                throw new RuntimeException("Service is off:" + ringMember);
            }
            if (random.nextInt(100) > (100 - oddsOfAConnectionFailureWhenTaking)) {
                throw new RuntimeException("Random take failure:" + ringMember);
            }

            try {
                ByteArrayOutputStream bytesOut = new ByteArrayOutputStream();
                Future<Object> submit = asIfOverTheWire.submit(() -> {
                    DataOutputStream dos = new DataOutputStream(new SnappyOutputStream(bytesOut));
                    amzaService.rowsStream(dos,
                        remoteRingMember,
                        localVersionedPartitionName,
                        localTxId,
                        leadershipToken);
                    dos.flush();
                    return null;
                });
                submit.get();
                StreamingTakesConsumer streamingTakesConsumer = new StreamingTakesConsumer();
                return streamingTakesConsumer.consume(new DataInputStream(new SnappyInputStream(new ByteArrayInputStream(bytesOut.toByteArray()))), rowStream);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        public void printService() throws Exception {
            if (off) {
                System.out.println(ringHost.getHost() + ":" + ringHost.getPort() + " is OFF flapped:" + flapped);
            }
        }

        public void printRings() {
            try {
                RingTopology systemRing = amzaService.getRingReader().getRing(AmzaRingReader.SYSTEM_RING);
                System.out.println("RING:"
                    + " me:" + amzaService.getRingReader().getRingMember()
                    + " ring:" + Lists.transform(systemRing.entries, input -> input.ringMember));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        public boolean isEmpty() throws Exception {
            Set<PartitionName> allAPartitions = amzaService.getPartitionNames();
            if (allAPartitions.isEmpty()) {
                return true;
            }

            for (PartitionName partitionName : allAPartitions) {
                if (!partitionName.isSystemPartition()) {
                    Partition partition = amzaService.getPartition(partitionName);
                    int[] count = {0};
                    partition.scan(null, null, null, null, (prefix, key, value, timestamp, version) -> {
                        count[0]++;
                        return true;
                    });
                    if (count[0] > 0) {
                        return false;
                    }
                }
            }
            return true;
        }

        private final Set<PartitionName> IGNORED_PARTITION_NAMES = ImmutableSet.of(PartitionCreator.HIGHWATER_MARK_INDEX.getPartitionName(),
            PartitionCreator.AQUARIUM_LIVELINESS_INDEX.getPartitionName());

        public boolean compare(AmzaNode service) throws Exception {
            if (off || service.off) {
                return true;
            }

            Set<PartitionName> allAPartitions = amzaService.getPartitionNames();
            Set<PartitionName> allBPartitions = service.amzaService.getPartitionNames();

            if (allAPartitions.size() != allBPartitions.size()) {
                System.out.println(allAPartitions + " -vs- " + allBPartitions);
                return false;
            }

            Set<PartitionName> partitionNames = new HashSet<>();
            partitionNames.addAll(allAPartitions);
            partitionNames.addAll(allBPartitions);

            RingTopology aRing = amzaService.getRingReader().getRing(AmzaRingReader.SYSTEM_RING);
            RingTopology bRing = service.amzaService.getRingReader().getRing(AmzaRingReader.SYSTEM_RING);

            if (!aRing.entries.equals(bRing.entries)) {
                System.out.println(aRing + "-vs-" + bRing);
                return false;
            }

            for (PartitionName partitionName : partitionNames) {
                if (IGNORED_PARTITION_NAMES.contains(partitionName)) {
                    continue;
                }

                Partition a = amzaService.getPartition(partitionName);
                Partition b = service.amzaService.getPartition(partitionName);
                if (a == null || b == null) {
                    System.out.println(partitionName + " " + amzaService.getRingReader().getRingMember() + " " + a + " -- vs --"
                        + service.amzaService.getRingReader().getRingMember() + " " + b);
                    return false;
                }
                if (!compare(partitionName, a, b)) {
                    return false;
                }
            }
            return true;
        }

        private void takePartitionUpdates(RingMember ringMember,
            TimestampedRingHost timestampedRingHost,
            long sessionId,
            long timeoutMillis,
            AvailableRowsTaker.AvailableStream updatedPartitionsStream,
            Callable<Void> deliverCallback,
            Callable<Void> pingCallback) {

            if (off) {
                throw new RuntimeException("Service is off:" + ringMember);
            }
            if (random.nextInt(100) > (100 - oddsOfAConnectionFailureWhenTaking)) {
                throw new RuntimeException("Random take failure:" + ringMember);
            }

            try {
                amzaService.availableRowsStream(ringMember,
                    timestampedRingHost,
                    sessionId,
                    timeoutMillis,
                    updatedPartitionsStream,
                    deliverCallback,
                    pingCallback);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        //  Use for testing
        private boolean compare(PartitionName partitionName, Partition a, Partition b) throws Exception {
            final MutableInt compared = new MutableInt(0);
            final MutableBoolean passed = new MutableBoolean(true);
            try {
                a.scan(null, null, null, null,
                    (prefix, key, aValue, aTimestamp, aVersion) -> {
                        try {
                            compared.increment();
                            long[] btimestamp = new long[1];
                            byte[][] bvalue = new byte[1][];
                            long[] bversion = new long[1];
                            b.get(Consistency.leader, prefix, stream -> stream.stream(key),
                                (_prefix, _key, value, timestamp, tombstoned, version) -> {
                                    if (timestamp != -1 && !tombstoned) {
                                        btimestamp[0] = timestamp;
                                        bvalue[0] = value;
                                        bversion[0] = version;
                                    }
                                    return true;
                                });

                            long bTimetamp = btimestamp[0];
                            byte[] bValue = bvalue[0];
                            long bVersion = bversion[0];
                            String comparing = new String(partitionName.getRingName()) + ":" + new String(partitionName.getName())
                            + " to " + new String(partitionName.getRingName()) + ":" + new String(partitionName.getName()) + "\n";

                            if (bValue == null) {
                                System.out.println("INCONSISTENCY: " + comparing + " " + Arrays.toString(aValue)
                                    + " != null"
                                    + "' \n" + Arrays.toString(aValue) + " vs null");
                                passed.setValue(false);
                                return false;
                            }
                            if (aTimestamp != bTimetamp) {
                                System.out.println("INCONSISTENCY: " + comparing + " timestamp:'" + aTimestamp
                                    + "' != '" + bTimetamp
                                    + "' \n" + Arrays.toString(aValue) + " vs " + Arrays.toString(bValue));
                                passed.setValue(false);
                                System.out.println("----------------------------------");
                                return false;
                            }
                            if (aVersion != bVersion) {
                                System.out.println("INCONSISTENCY: " + comparing + " version:'" + aVersion
                                    + "' != '" + bVersion
                                    + "' \n" + Arrays.toString(aValue) + " vs " + Arrays.toString(bValue));
                                passed.setValue(false);
                                System.out.println("----------------------------------");
                                return false;
                            }
                            if (aValue == null && bValue != null) {
                                System.out.println("INCONSISTENCY: " + comparing + " null"
                                    + " != '" + Arrays.toString(bValue)
                                    + "' \n" + "null" + " vs " + Arrays.toString(bValue));
                                passed.setValue(false);
                                return false;
                            }
                            if (aValue != null && !Arrays.equals(aValue, bValue)) {
                                System.out.println("INCONSISTENCY: " + comparing + " value:'" + Arrays.toString(aValue)
                                    + "' != '" + Arrays.toString(bValue)
                                    + "' \n" + Arrays.toString(aValue) + " vs " + Arrays.toString(bValue));
                                passed.setValue(false);
                                return false;
                            }
                            return true;
                        } catch (Exception x) {
                            throw new RuntimeException("Failed while comparing", x);
                        }
                    });
            } catch (Exception e) {
                System.out.println("EXCEPTION: " + e.getMessage());
                passed.setValue(false);
            }

            System.out.println(
                "partition:" + new String(partitionName.getName()) + " vs:" + new String(partitionName.getName()) + " compared:" + compared + " keys");
            return passed.booleanValue();
        }
    }
}
