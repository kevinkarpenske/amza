package com.jivesoftware.os.amza.service.take;

import com.google.common.collect.Lists;
import com.jivesoftware.os.amza.api.ring.RingHost;
import com.jivesoftware.os.amza.api.ring.RingMember;
import com.jivesoftware.os.amza.api.ring.RingMemberAndHost;
import com.jivesoftware.os.amza.service.ring.RingTopology;
import com.jivesoftware.os.amza.service.take.TakeRingCoordinator.VersionedRing;
import java.util.Arrays;
import java.util.List;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

/**
 *
 */
public class VersionedRingTest {

    @Test
    public void testCategories() {
        List<RingMemberAndHost> ring = Arrays.asList(memberAndHost("a"), memberAndHost("b"), memberAndHost("c"), memberAndHost("d"), memberAndHost("e"));
        VersionedRing versionedRing = VersionedRing.compute(
            new RingTopology(-1, -1, ring, 0));
        assertNull(versionedRing.getCategory(member("a")));
        assertEquals(1, versionedRing.getCategory(member("b")).intValue());
        assertEquals(1, versionedRing.getCategory(member("c")).intValue());
        assertEquals(2, versionedRing.getCategory(member("d")).intValue());
        assertEquals(2, versionedRing.getCategory(member("e")).intValue());

        versionedRing = VersionedRing.compute(
            new RingTopology(-1, -1, ring, 1));
        assertEquals(2, versionedRing.getCategory(member("a")).intValue());
        assertNull(versionedRing.getCategory(member("b")));
        assertEquals(1, versionedRing.getCategory(member("c")).intValue());
        assertEquals(1, versionedRing.getCategory(member("d")).intValue());
        assertEquals(2, versionedRing.getCategory(member("e")).intValue());

        versionedRing = VersionedRing.compute(
            new RingTopology(-1, -1, ring, 2));
        assertEquals(2, versionedRing.getCategory(member("a")).intValue());
        assertEquals(2, versionedRing.getCategory(member("b")).intValue());
        assertNull(versionedRing.getCategory(member("c")));
        assertEquals(1, versionedRing.getCategory(member("d")).intValue());
        assertEquals(1, versionedRing.getCategory(member("e")).intValue());

        versionedRing = VersionedRing.compute(
            new RingTopology(-1, -1, ring, 3));
        assertEquals(1, versionedRing.getCategory(member("a")).intValue());
        assertEquals(2, versionedRing.getCategory(member("b")).intValue());
        assertEquals(2, versionedRing.getCategory(member("c")).intValue());
        assertNull(versionedRing.getCategory(member("d")));
        assertEquals(1, versionedRing.getCategory(member("e")).intValue());
    }

    @Test
    public void testTakeFromFactor() {
        // using simple quorum method
        List<RingMemberAndHost> ring = Lists.newArrayList();
        ring.add(memberAndHost("1"));
        assertEquals(new RingTopology(-1, -1, ring, -1).getTakeFromFactor(), 1);
        assertEquals(new RingTopology(-1, -1, ring, 0).getTakeFromFactor(), 1);

        ring.addAll(Arrays.asList(memberAndHost("2"), memberAndHost("3"), memberAndHost("4")));
        assertEquals(new RingTopology(-1, -1, ring, -1).getTakeFromFactor(), 2);
        assertEquals(new RingTopology(-1, -1, ring, 0).getTakeFromFactor(), 2);

        ring.addAll(Arrays.asList(memberAndHost("5")));
        assertEquals(new RingTopology(-1, -1, ring, -1).getTakeFromFactor(), 2);
        assertEquals(new RingTopology(-1, -1, ring, 0).getTakeFromFactor(), 2);

        ring.addAll(Arrays.asList(memberAndHost("6"), memberAndHost("7"), memberAndHost("8")));
        assertEquals(new RingTopology(-1, -1, ring, -1).getTakeFromFactor(), 4);
        assertEquals(new RingTopology(-1, -1, ring, 0).getTakeFromFactor(), 4);

        ring.addAll(Arrays.asList(memberAndHost("9")));
        assertEquals(new RingTopology(-1, -1, ring, -1).getTakeFromFactor(), 4);
        assertEquals(new RingTopology(-1, -1, ring, 0).getTakeFromFactor(), 4);

        // using simple logarithmic method
        /*List<RingMemberAndHost> ring = Lists.newArrayList();
        ring.add(memberAndHost("1"));
        assertEquals(new RingTopology(-1, -1, ring, -1).getTakeFromFactor(), 1);
        assertEquals(new RingTopology(-1, -1, ring, 0).getTakeFromFactor(), 1);

        ring.addAll(Arrays.asList(memberAndHost("2"), memberAndHost("3"), memberAndHost("4")));
        assertEquals(new RingTopology(-1, -1, ring, -1).getTakeFromFactor(), 1);
        assertEquals(new RingTopology(-1, -1, ring, 0).getTakeFromFactor(), 1);

        ring.addAll(Arrays.asList(memberAndHost("5")));
        assertEquals(new RingTopology(-1, -1, ring, -1).getTakeFromFactor(), 2);
        assertEquals(new RingTopology(-1, -1, ring, 0).getTakeFromFactor(), 2);

        ring.addAll(Arrays.asList(memberAndHost("6"), memberAndHost("7"), memberAndHost("8")));
        assertEquals(new RingTopology(-1, -1, ring, -1).getTakeFromFactor(), 2);
        assertEquals(new RingTopology(-1, -1, ring, 0).getTakeFromFactor(), 2);

        ring.addAll(Arrays.asList(memberAndHost("9")));
        assertEquals(new RingTopology(-1, -1, ring, -1).getTakeFromFactor(), 3);
        assertEquals(new RingTopology(-1, -1, ring, 0).getTakeFromFactor(), 3);*/
    }

    private RingMemberAndHost memberAndHost(String name) {
        return new RingMemberAndHost(member(name), new RingHost("datacenter", "rack", name, 1));
    }

    private RingMember member(String name) {
        return new RingMember(name);
    }
}
