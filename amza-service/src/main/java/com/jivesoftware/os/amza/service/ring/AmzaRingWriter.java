package com.jivesoftware.os.amza.service.ring;

import com.jivesoftware.os.amza.api.ring.RingHost;
import com.jivesoftware.os.amza.api.ring.RingMember;

/**
 * @author jonathan.colt
 */
public interface AmzaRingWriter {

    void ensureMaximalRing(byte[] ringName, long timeoutInMillis) throws Exception;

    void ensureSubRing(byte[] ringName, int desiredRingSize, long timeoutInMillis) throws Exception;

    void deregister(RingMember ringMember) throws Exception;

    void register(RingMember ringMember, RingHost ringHost, long timestampId, boolean force) throws Exception;

    void addRingMember(byte[] ringName, RingMember ringMember) throws Exception;

    void removeRingMember(byte[] ringName, RingMember ringHost) throws Exception;

}
