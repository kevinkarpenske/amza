package com.jivesoftware.os.amza.ui.region;

import com.google.common.base.Optional;
import com.google.common.collect.Maps;
import com.jivesoftware.os.amza.api.ring.RingHost;
import com.jivesoftware.os.amza.api.ring.RingMember;
import com.jivesoftware.os.amza.service.ring.AmzaRingReader;
import com.jivesoftware.os.amza.service.ring.AmzaRingWriter;
import com.jivesoftware.os.amza.ui.soy.SoyRenderer;
import com.jivesoftware.os.mlogger.core.MetricLogger;
import com.jivesoftware.os.mlogger.core.MetricLoggerFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 *
 */
// soy.page.amzaRingPluginRegion
public class AmzaRingsPluginRegion implements PageRegion<Optional<AmzaRingsPluginRegion.AmzaRingsPluginRegionInput>> {

    private static final MetricLogger log = MetricLoggerFactory.getLogger();

    private final String template;
    private final SoyRenderer renderer;
    private final AmzaRingWriter ringWriter;
    private final AmzaRingReader ringReader;

    public AmzaRingsPluginRegion(String template,
        SoyRenderer renderer,
        AmzaRingWriter ringWriter,
        AmzaRingReader ringReader) {
        this.template = template;
        this.renderer = renderer;
        this.ringWriter = ringWriter;
        this.ringReader = ringReader;
    }

    public static class AmzaRingsPluginRegionInput {

        final String ringName;
        final String status;
        final String member;
        final String action;

        public AmzaRingsPluginRegionInput(String ringName, String status, String member, String action) {
            this.ringName = ringName;
            this.status = status;
            this.member = member;
            this.action = action;
        }

    }

    @Override
    public String render(Optional<AmzaRingsPluginRegionInput> optionalInput) {
        Map<String, Object> data = Maps.newHashMap();

        try {
            if (optionalInput.isPresent()) {
                final AmzaRingsPluginRegionInput input = optionalInput.get();

                if (input.action.equals("add")) {
                    ringWriter.addRingMember(input.ringName.getBytes(), new RingMember(input.member));
                } else if (input.action.equals("remove")) {
                    ringWriter.removeRingMember(input.ringName.getBytes(), new RingMember(input.member));
                }

                final List<Map<String, String>> rows = new ArrayList<>();
                ringReader.allRings((byte[] ringName, RingMember ringMember, RingHost ringHost) -> {
                    if ((input.ringName.isEmpty() || new String(ringName).contains(input.ringName))
                        && (input.member.isEmpty() || "".contains(input.member))
                        && (input.status.isEmpty() || "".contains(input.status))) {

                        Map<String, String> row = new HashMap<>();
                        row.put("ringName", new String(ringName));
                        row.put("member", ringMember.getMember());
                        row.put("host", ringHost.getHost());
                        row.put("port", String.valueOf(ringHost.getPort()));
                        rows.add(row);
                    }
                    return true;
                });
                data.put("rings", rows);
            }
        } catch (Exception e) {
            log.error("Unable to retrieve data", e);
        }

        return renderer.render(template, data);
    }

    @Override
    public String getTitle() {
        return "Amza Rings";
    }

}
