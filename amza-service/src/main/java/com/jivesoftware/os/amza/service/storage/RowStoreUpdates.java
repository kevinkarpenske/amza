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
package com.jivesoftware.os.amza.service.storage;

import com.google.common.base.Optional;
import com.jivesoftware.os.amza.service.replication.RegionStripe;
import com.jivesoftware.os.amza.shared.RegionName;
import com.jivesoftware.os.amza.shared.RowsChanged;
import com.jivesoftware.os.amza.shared.WALKey;
import com.jivesoftware.os.amza.shared.WALReplicator;
import com.jivesoftware.os.amza.shared.WALStorageUpdateMode;
import com.jivesoftware.os.amza.shared.WALValue;
import com.jivesoftware.os.amza.shared.stats.AmzaStats;

public class RowStoreUpdates {

    private final AmzaStats amzaStats;
    private final RegionName regionName;
    private final RegionStripe regionStripe;
    private final RowsStorageUpdates rowsStorageChangeSet;
    private int changedCount = 0;

    public RowStoreUpdates(AmzaStats amzaStats, RegionName regionName, RegionStripe regionStripe, RowsStorageUpdates rowsStorageChangeSet) {
        this.amzaStats = amzaStats;
        this.regionName = regionName;
        this.regionStripe = regionStripe;
        this.rowsStorageChangeSet = rowsStorageChangeSet;
    }

    public WALKey put(WALKey key, WALValue value) throws Exception {
        if (rowsStorageChangeSet.put(key, value)) {
            changedCount++;
        }
        return key;
    }

    public void commit(WALReplicator replicator) throws Exception {
        commit(replicator, WALStorageUpdateMode.replicateThenUpdate);
    }

    public void commit(WALReplicator replicator, WALStorageUpdateMode mode) throws Exception {
        if (changedCount > 0) {
            RowsChanged commit = regionStripe.commit(regionName, Optional.absent(), replicator, mode, rowsStorageChangeSet);
            amzaStats.direct(regionName, changedCount, commit.getOldestRowTxId());
        }
    }
}
