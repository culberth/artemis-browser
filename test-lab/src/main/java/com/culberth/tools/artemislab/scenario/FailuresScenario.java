package com.culberth.tools.artemislab.scenario;

import static com.culberth.tools.artemislab.scenario.DeliveryScenario.counts;

import com.culberth.tools.artemislab.LabException;
import com.culberth.tools.artemislab.broker.BrokerProfile;
import com.culberth.tools.artemislab.broker.ManagementClient;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.springframework.stereotype.Component;

/**
 * {@code FAILURES} (E01, E02; E03 is <em>Interrupt broker</em> on the lab page): on a broker provisioned with the
 * <em>Restricted users</em> profile, proves that the two test users are refused exactly what the profile says before a
 * person signs Artemis Browser in as them.
 *
 * <ul>
 * <li>{@code viewer} (password {@code viewer}) may manage, and is refused the acceptors, disk usage, diverts, prepared
 * transactions, roles and every address attribute — so some panels are denied and the rest must still stand (E01).</li>
 * <li>{@code nomanage} (password {@code nomanage}) may connect and is refused all management — the required failure
 * (E02).</li>
 * </ul>
 *
 * A queue {@code failures} with 5 messages gives the pages something to show.
 */
@Component
public class FailuresScenario implements Recipe
{

    public static final String ID = "FAILURES";

    @Override
    public String id()
    {
        return ID;
    }

    @Override
    public Set<BrokerProfile> profiles()
    {
        return Set.of(BrokerProfile.RESTRICTED);
    }

    @Override
    public String run(Fixture f, Map<String, Integer> params) throws Exception
    {
        String queue = f.name("failures");
        f.requireNew(List.of(queue));
        f.reserve(5L * 40);
        f.createQueue(queue);
        try (Sender s = f.sender())
        {
            for (int i = 1; i <= 5; i++)
            {
                s.send(queue, i, s.session().createTextMessage(BasicScenario.pad("failures " + i, 40)), "text", 40, "",
                        Sender.Options.PERSISTENT);
            }
        }
        List<String> asserted = new ArrayList<>();
        asserted.add(f.await(queue, counts("messageCount", 5)));

        try (Connection connection = f.connectionAs("viewer");
                ManagementClient viewer = new ManagementClient(connection, f.limits().operationTimeout()))
        {
            asserted.add(f.assertThat(viewer.queueNames().contains(queue), "viewer may list queues"));
            asserted.add(denied(f, "viewer is denied getAcceptorsAsJSON",
                    () -> viewer.invoke(ResourceNames.BROKER, "getAcceptorsAsJSON")));
            // An attribute refused by RBAC reads exactly like a missing one ("Problem while retrieving attribute"), so
            // each is checked as readable by the admin first: then the viewer's failure can only be the refusal.
            f.management().attribute(ResourceNames.BROKER, "diskStoreUsage");
            f.management().attribute(ResourceNames.ADDRESS + queue, "numberOfMessages");
            asserted.add(deniedAttribute(f, "viewer is denied the diskStoreUsage attribute",
                    () -> viewer.attribute(ResourceNames.BROKER, "diskStoreUsage")));
            asserted.add(deniedAttribute(f, "viewer is denied address attributes",
                    () -> viewer.attribute(ResourceNames.ADDRESS + queue, "numberOfMessages")));
        }
        try (Connection connection = f.connectionAs("nomanage"))
        {
            asserted.add(denied(f, "nomanage is denied management entirely", () ->
            {
                try (ManagementClient nomanage = new ManagementClient(connection, f.limits().operationTimeout()))
                {
                    nomanage.queueNames();
                }
            }));
        }
        return "Asserted " + asserted.size() + " conditions. Sign Artemis Browser in as viewer/viewer and "
                + "nomanage/nomanage to compare.";
    }

    @FunctionalInterface
    private interface Call
    {
        void run() throws Exception;
    }

    /** Passes when the call is refused for permission, and records the broker's words; anything else fails. */
    private static String denied(Fixture f, String what, Call call)
    {
        return refused(f, what, call, false);
    }

    /**
     * As {@link #denied} for an attribute: the broker gives no permission error for those, only "Problem while
     * retrieving attribute" — indistinguishable from a missing attribute, which is why the caller reads it as admin
     * first.
     */
    private static String deniedAttribute(Fixture f, String what, Call call)
    {
        return refused(f, what, call, true);
    }

    private static String refused(Fixture f, String what, Call call, boolean attribute)
    {
        // The assertion is recorded outside the try: a failed assertion throws a LabException of its own, which must
        // not be mistaken for the broker's refusal.
        String refusal;
        try
        {
            call.run();
            refusal = null;
        }
        catch (JMSException | LabException e)
        {
            refusal = String.valueOf(e.getMessage());
        }
        catch (Exception e)
        {
            return f.assertThat(false, what + " — failed otherwise: " + e);
        }
        if (refusal == null)
        {
            return f.assertThat(false, what + " — but it was allowed");
        }
        boolean permission = refusal.contains("AMQ229032") || refusal.toLowerCase().contains("permission")
                || attribute && refusal.contains("Problem while retrieving attribute");
        return f.assertThat(permission, what + ": " + refusal);
    }
}
