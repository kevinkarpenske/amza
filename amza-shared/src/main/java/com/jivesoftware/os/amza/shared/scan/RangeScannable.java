package com.jivesoftware.os.amza.shared.scan;

import com.jivesoftware.os.amza.shared.wal.WALKey;

/**
 *
 * @author jonathan.colt
 */
public interface RangeScannable<S> extends Scannable<S> {

    void rangeScan(WALKey from, WALKey to, Scan<S> scan) throws Exception;
}
