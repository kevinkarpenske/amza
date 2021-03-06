package com.jivesoftware.os.amza.service.ring;

import com.jivesoftware.os.amza.api.ring.RingHost;
import com.jivesoftware.os.amza.api.ring.RingMember;

/**
 *
 * @author jonathan.colt
 */
public interface AmzaRingReader {

    byte[] SYSTEM_RING = "system".getBytes();

    RingMember getRingMember();

    /*List<Entry<RingMember, RingHost>> getNeighbors(byte[] ringName) throws Exception;*/

    RingTopology getRing(byte[] ringName, long timeoutInMillis) throws Exception;

    RingSet getRingSet(RingMember ringMember, long timeoutInMillis) throws Exception;

    int getRingSize(byte[] ringName, long timeoutInMillis) throws Exception;

    int getTakeFromFactor(byte[] ringName, long timeoutInMillis) throws Exception;

    void allRings(RingStream ringStream) throws Exception;

    interface RingStream {

        boolean stream(byte[] ringName, RingMember ringMember, RingHost ringHost) throws Exception;
    }

    void streamRingNames(RingMember ringMember, long timeoutInMillis, RingNameStream ringNameStream) throws Exception;

    interface RingNameStream {

        boolean stream(byte[] ringName, int ringHash) throws Exception;
    }
}
