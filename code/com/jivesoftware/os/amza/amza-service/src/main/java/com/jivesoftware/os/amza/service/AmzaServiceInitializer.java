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

import com.google.common.base.Optional;
import com.jivesoftware.os.amza.service.replication.AmzaRegionChangeReceiver;
import com.jivesoftware.os.amza.service.replication.AmzaRegionChangeReplicator;
import com.jivesoftware.os.amza.service.replication.AmzaRegionChangeTaker;
import com.jivesoftware.os.amza.service.replication.AmzaRegionCompactor;
import com.jivesoftware.os.amza.service.replication.RegionBackHighwaterMarks;
import com.jivesoftware.os.amza.service.replication.SendFailureListener;
import com.jivesoftware.os.amza.service.replication.TakeFailureListener;
import com.jivesoftware.os.amza.service.storage.RegionPropertyMarshaller;
import com.jivesoftware.os.amza.service.storage.RegionProvider;
import com.jivesoftware.os.amza.service.storage.WALs;
import com.jivesoftware.os.amza.service.storage.delta.DeltaWALFactory;
import com.jivesoftware.os.amza.service.storage.delta.DeltaWALStorage;
import com.jivesoftware.os.amza.service.storage.delta.MemoryBackedDeltaWALStorage;
import com.jivesoftware.os.amza.shared.RingHost;
import com.jivesoftware.os.amza.shared.RowChanges;
import com.jivesoftware.os.amza.shared.RowsChanged;
import com.jivesoftware.os.amza.shared.UpdatesSender;
import com.jivesoftware.os.amza.shared.UpdatesTaker;
import com.jivesoftware.os.amza.shared.WALReplicator;
import com.jivesoftware.os.amza.shared.WALStorageProvider;
import com.jivesoftware.os.amza.shared.stats.AmzaStats;
import com.jivesoftware.os.amza.storage.binary.BinaryRowMarshaller;
import com.jivesoftware.os.jive.utils.ordered.id.TimestampedOrderIdProvider;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.io.File;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

public class AmzaServiceInitializer {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();

    public static class AmzaServiceConfig {

        public String[] workingDirectories = new String[] { "./var/data/" };

        public int resendReplicasIntervalInMillis = 1000;
        public int applyReplicasIntervalInMillis = 1000;
        public int takeFromNeighborsIntervalInMillis = 1000;

        public long checkIfCompactionIsNeededIntervalInMillis = 60_000;
        public long compactTombstoneIfOlderThanNMillis = 30 * 24 * 60 * 60 * 1000L;

        public int numberOfResendThreads = 8;
        public int numberOfApplierThreads = 8;
        public int numberOfCompactorThreads = 8;
        public int numberOfTakerThreads = 8;
        public int numberOfReplicatorThreads = 24;

    }

    public AmzaService initialize(AmzaServiceConfig config,
        AmzaStats amzaStats,
        BinaryRowMarshaller rowMarshaller,
        RingHost ringHost,
        TimestampedOrderIdProvider orderIdProvider,
        RegionPropertyMarshaller regionPropertyMarshaller,
        WALStorageProvider regionsWALStorageProvider,
        WALStorageProvider replicaWALStorageProvider,
        WALStorageProvider resendWALStorageProvider,
        UpdatesSender updatesSender,
        UpdatesTaker updatesTaker,
        Optional<SendFailureListener> sendFailureListener,
        Optional<TakeFailureListener> takeFailureListener,
        final RowChanges allRowChanges) throws Exception {

        AmzaRegionWatcher amzaRegionWatcher = new AmzaRegionWatcher(allRowChanges);

        final AtomicReference<AmzaRegionChangeReplicator> replicator = new AtomicReference<>();

        WALReplicator walReplicator = new WALReplicator() {
            @Override
            public Future<Boolean> replicate(RowsChanged rowsChanged) throws Exception {
                return replicator.get().replicate(rowsChanged);
            }
        };

        int deltaStorageStripes = 3;
        long maxUpdatesBeforeCompaction = 400_000;
        long compactAfterNUpdates = maxUpdatesBeforeCompaction / deltaStorageStripes;
        DeltaWALStorage[] deltaWALStorages = new DeltaWALStorage[deltaStorageStripes];
        for (int i = 0; i < deltaWALStorages.length; i++) {
            File walDir = new File(config.workingDirectories[i % config.workingDirectories.length], "delta-wal-" + i);
            DeltaWALFactory deltaWALFactory = new DeltaWALFactory(orderIdProvider, walDir, amzaStats.ioStats, rowMarshaller);
            deltaWALStorages[i] = new MemoryBackedDeltaWALStorage(i, rowMarshaller, deltaWALFactory, walReplicator, compactAfterNUpdates);
        }

        final RegionProvider regionProvider = new RegionProvider(amzaStats,
            orderIdProvider,
            regionPropertyMarshaller,
            deltaWALStorages,
            config.workingDirectories,
            "amza/stores",
            regionsWALStorageProvider,
            amzaRegionWatcher,
            walReplicator);

        RegionBackHighwaterMarks highwaterMarks = new RegionBackHighwaterMarks(orderIdProvider, ringHost, regionProvider, 1000);
        final AmzaHostRing amzaRing = new AmzaHostRing(ringHost, regionProvider, orderIdProvider);
        WALs resendWALs = new WALs(config.workingDirectories, "amza/WAL/resend", resendWALStorageProvider);
        resendWALs.load();

        AmzaRegionChangeReplicator changeReplicator = new AmzaRegionChangeReplicator(amzaStats,
            rowMarshaller,
            amzaRing,
            regionProvider,
            resendWALs,
            updatesSender,
            Executors.newFixedThreadPool(config.numberOfReplicatorThreads),
            sendFailureListener,
            config.resendReplicasIntervalInMillis,
            config.numberOfResendThreads);

        replicator.set(changeReplicator);

        WALs replicatedWALs = new WALs(config.workingDirectories, "amza/WAL/replicated", replicaWALStorageProvider);
        replicatedWALs.load();

        AmzaRegionChangeReceiver changeReceiver = new AmzaRegionChangeReceiver(amzaStats,
            rowMarshaller,
            regionProvider,
            replicatedWALs,
            config.applyReplicasIntervalInMillis,
            config.numberOfApplierThreads
        );

        AmzaRegionChangeTaker changeTaker = new AmzaRegionChangeTaker(amzaStats,
            amzaRing,
            regionProvider,
            highwaterMarks,
            updatesTaker,
            takeFailureListener,
            config.takeFromNeighborsIntervalInMillis,
            config.numberOfTakerThreads);

        AmzaRegionCompactor regionCompactor = new AmzaRegionCompactor(amzaStats,
            regionProvider,
            orderIdProvider,
            config.checkIfCompactionIsNeededIntervalInMillis,
            config.compactTombstoneIfOlderThanNMillis,
            config.numberOfCompactorThreads);

        return new AmzaService(orderIdProvider,
            amzaRing,
            highwaterMarks,
            changeReceiver,
            changeTaker,
            changeReplicator,
            regionCompactor,
            regionProvider,
            amzaRegionWatcher);
    }
}
