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
package com.jivesoftware.os.amza.shared.take;

import com.jivesoftware.os.amza.shared.region.RegionName;
import com.jivesoftware.os.amza.shared.ring.RingHost;
import com.jivesoftware.os.amza.shared.ring.RingMember;
import com.jivesoftware.os.amza.shared.scan.RowStream;
import java.util.Map;
import java.util.Map.Entry;

public interface UpdatesTaker {

    StreamingTakeResult streamingTakeUpdates(RingMember taker,
        RingHost takerHost,
        Entry<RingMember, RingHost> node,
        RegionName partitionName,
        long transactionId,
        RowStream tookRowUpdates);

    class StreamingTakeResult {

        public final Throwable unreachable;
        public final Throwable error;
        public final Map<RingMember, Long> otherHighwaterMarks;

        public StreamingTakeResult(Exception unreachable,
            Exception error,
            Map<RingMember, Long> otherHighwaterMarks) {
            this.unreachable = unreachable;
            this.error = error;
            this.otherHighwaterMarks = otherHighwaterMarks;
        }
    }
}
