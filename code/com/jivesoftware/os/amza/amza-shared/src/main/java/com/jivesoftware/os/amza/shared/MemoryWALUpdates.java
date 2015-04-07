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
package com.jivesoftware.os.amza.shared;

import java.util.Map;
import java.util.Map.Entry;

public class MemoryWALUpdates implements Scannable<WALValue> {

    private final Map<WALKey, WALValue> updates;

    public MemoryWALUpdates(Map<WALKey, WALValue> updates) {
        this.updates = updates;
    }

    @Override
    public void rowScan(Scan<WALValue> scan) throws Exception {
        for (Entry<WALKey, WALValue> e : updates.entrySet()) {
            WALKey key = e.getKey();
            WALValue value = e.getValue();
            if (!scan.row(-1, key, value)) {
                break;
            }
        }
    }
}
