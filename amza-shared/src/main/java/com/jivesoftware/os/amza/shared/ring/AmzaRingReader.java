package com.jivesoftware.os.amza.shared.ring;

import java.util.List;
import java.util.Map.Entry;
import java.util.NavigableMap;

/**
 *
 * @author jonathan.colt
 */
public interface AmzaRingReader {

    byte[] SYSTEM_RING = "system".getBytes();

    RingMember getRingMember();

    List<Entry<RingMember, RingHost>> getNeighbors(byte[] ringName) throws Exception;

    NavigableMap<RingMember, RingHost> getRing(byte[] ringName) throws Exception;

    int getRingSize(byte[] ringName) throws Exception;

    void allRings(RingStream ringStream) throws Exception;

    interface RingStream {

        boolean stream(byte[] ringName, RingMember ringMember, RingHost ringHost) throws Exception;
    }

    void getRingNames(RingMember ringMember, RingNameStream ringNameStream) throws Exception;

    interface RingNameStream {

        boolean stream(byte[] ringName) throws Exception;
    }
}
