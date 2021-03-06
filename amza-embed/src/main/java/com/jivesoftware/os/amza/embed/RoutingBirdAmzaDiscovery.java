package com.jivesoftware.os.amza.embed;

import com.google.common.base.Strings;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.jivesoftware.os.amza.api.ring.RingHost;
import com.jivesoftware.os.amza.api.ring.RingMember;
import com.jivesoftware.os.amza.service.AmzaRingStoreWriter;
import com.jivesoftware.os.amza.service.AmzaService;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import com.jivesoftware.os.routing.bird.deployable.Deployable;
import com.jivesoftware.os.routing.bird.shared.ConnectionDescriptor;
import com.jivesoftware.os.routing.bird.shared.ConnectionDescriptors;
import com.jivesoftware.os.routing.bird.shared.HostPort;
import com.jivesoftware.os.routing.bird.shared.InstanceDescriptor;
import com.jivesoftware.os.routing.bird.shared.TenantRoutingProvider;
import com.jivesoftware.os.routing.bird.shared.TenantsServiceConnectionDescriptorProvider;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @author jonathan.colt
 */
public class RoutingBirdAmzaDiscovery implements Runnable {

    private static final MetricLogger LOG = MetricLoggerFactory.getLogger();
    private final ScheduledExecutorService scheduledExecutorService = Executors.newScheduledThreadPool(1,
        new ThreadFactoryBuilder().setNameFormat("routing-bird-discovery-%d").build());

    private final Deployable deployable;
    private final String serviceName;
    private final AmzaService amzaService;
    private final long discoveryIntervalMillis;
    private final Set<RingMember> blacklistRingMembers;
    private final AtomicInteger systemRingSize;

    public RoutingBirdAmzaDiscovery(Deployable deployable,
        String serviceName,
        AmzaService amzaService,
        long discoveryIntervalMillis,
        Set<RingMember> blacklistRingMembers,
        AtomicInteger systemRingSize) {
        this.deployable = deployable;
        this.serviceName = serviceName;
        this.amzaService = amzaService;
        this.discoveryIntervalMillis = discoveryIntervalMillis;
        this.blacklistRingMembers = blacklistRingMembers;
        this.systemRingSize = systemRingSize;
    }

    public void start() {
        scheduledExecutorService.scheduleWithFixedDelay(this, 0, discoveryIntervalMillis, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        scheduledExecutorService.shutdownNow();
    }

    @Override
    public void run() {
        try {
            TenantRoutingProvider tenantRoutingProvider = deployable.getTenantRoutingProvider();
            TenantsServiceConnectionDescriptorProvider connections = tenantRoutingProvider.getConnections(serviceName, "main", discoveryIntervalMillis);
            ConnectionDescriptors selfConnections = connections.getConnections("");
            for (ConnectionDescriptor connectionDescriptor : selfConnections.getConnectionDescriptors()) {

                InstanceDescriptor routingInstanceDescriptor = connectionDescriptor.getInstanceDescriptor();
                RingMember routingRingMember = new RingMember(
                    Strings.padStart(String.valueOf(routingInstanceDescriptor.instanceName), 5, '0') + "_" + routingInstanceDescriptor.instanceKey);

                if (!blacklistRingMembers.contains(routingRingMember)) {
                    HostPort hostPort = connectionDescriptor.getHostPort();
                    InstanceDescriptor instanceDescriptor = connectionDescriptor.getInstanceDescriptor();
                    AmzaRingStoreWriter ringWriter = amzaService.getRingWriter();
                    ringWriter.register(routingRingMember,
                        new RingHost(instanceDescriptor.datacenter,
                            instanceDescriptor.rack,
                            hostPort.getHost(),
                            hostPort.getPort()),
                        -1,
                        false);
                }
            }
            systemRingSize.set(selfConnections.getConnectionDescriptors().size());
        } catch (Exception x) {
            LOG.warn("Failed while calling routing bird discovery.", x);
        }
    }
}
