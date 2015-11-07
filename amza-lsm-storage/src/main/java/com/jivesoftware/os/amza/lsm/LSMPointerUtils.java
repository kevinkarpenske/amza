package com.jivesoftware.os.amza.lsm;

import com.jivesoftware.os.amza.api.filer.UIO;
import com.jivesoftware.os.amza.lsm.api.NextPointer;
import com.jivesoftware.os.amza.lsm.api.RawNextPointer;
import com.jivesoftware.os.amza.lsm.api.RawPointGet;

/**
 *
 * @author jonathan.colt
 */
public class LSMPointerUtils {

    public static NextPointer rawToReal(byte[] key, RawPointGet rawNextPointer) throws Exception {

        return (stream) -> rawNextPointer.next(key, (rawEntry, offset, length) -> {

            if (rawEntry == null) {
                return stream.stream(null, -1, false, -1, -1);
            }
            int keyLength = UIO.bytesInt(rawEntry, offset);
            byte[] k = new byte[keyLength];
            System.arraycopy(rawEntry, 4, k, 0, keyLength);
            long timestamp = UIO.bytesLong(rawEntry, offset + 4 + keyLength);
            boolean tombstone = rawEntry[offset + 4 + keyLength + 8] != 0;
            long version = UIO.bytesLong(rawEntry, offset + 4 + keyLength + 8 + 1);
            long walPointerFp = UIO.bytesLong(rawEntry, offset + 4 + keyLength + 8 + 1 + 8);

            return stream.stream(k, timestamp, tombstone, version, walPointerFp);
        });
    }

    public static NextPointer rawToReal(RawNextPointer rawNextPointer) throws Exception {

        return (stream) -> rawNextPointer.next((rawEntry, offset, length) -> {

            if (rawEntry == null) {
                return stream.stream(null, -1, false, -1, -1);
            }
            int keyLength = UIO.bytesInt(rawEntry, offset);
            byte[] key = new byte[keyLength];
            System.arraycopy(rawEntry, 4, key, 0, keyLength);
            long timestamp = UIO.bytesLong(rawEntry, offset + 4 + keyLength);
            boolean tombstone = rawEntry[offset + 4 + keyLength + 8] != 0;
            long version = UIO.bytesLong(rawEntry, offset + 4 + keyLength + 8 + 1);
            long walPointerFp = UIO.bytesLong(rawEntry, offset + 4 + keyLength + 8 + 1 + 8);

            return stream.stream(key, timestamp, tombstone, version, walPointerFp);
        });
    }
}
