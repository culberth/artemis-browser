package com.culberth.tools.artemisbrowser.broker;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.springframework.stereotype.Service;

/**
 * Reads the broker's diverts.
 *
 * <p>
 * There is no listing operation for them — {@code listDivertsAsJSON} and {@code listDiverts} both answer "no operation"
 * on 2.44.0 — so this is {@code getDivertNames} and then one attribute read per field per divert. Brokers carry a
 * handful of diverts, not hundreds, which is what makes that acceptable on the diagnose page.
 */
@Service
public class DivertDirectory
{

    private final BrokerSession brokerSession;

    public DivertDirectory(BrokerSession brokerSession)
    {
        this.brokerSession = brokerSession;
    }

    public List<Divert> all()
    {
        ManagementChannel management = brokerSession.requireManagement();
        List<Divert> diverts = new ArrayList<>();
        Map<String, BrokerException> unread = new LinkedHashMap<>();
        for (String name : names(management.invoke(ResourceNames.BROKER, "getDivertNames")))
        {
            String resource = ResourceNames.DIVERT + name;
            try
            {
                diverts.add(new Divert(name, string(management.attribute(resource, "address")),
                        string(management.attribute(resource, "forwardingAddress")),
                        string(management.attribute(resource, "filter")),
                        Boolean.TRUE.equals(management.attribute(resource, "exclusive")),
                        string(management.attribute(resource, "routingType")),
                        string(management.attribute(resource, "transformerClassName"))));
            }
            catch (BrokerException e)
            {
                // Destroyed between the name listing and this read — or refused: a divert attribute
                // the broker will not give reads the same either way. Checked below. A lost
                // connection is not a BrokerException, so it still propagates.
                unread.put(name, e);
            }
        }
        if (!unread.isEmpty())
        {
            // Still listed means not destroyed, so leaving it out would be a silent gap in the
            // list. Say the list is incomplete instead.
            List<String> still = names(management.invoke(ResourceNames.BROKER, "getDivertNames"));
            for (Map.Entry<String, BrokerException> failed : unread.entrySet())
            {
                if (still.contains(failed.getKey()))
                {
                    Availability why = failed.getValue() instanceof ManagementRefusal refusal ? refusal.availability()
                            : Availability.FAILED;
                    throw new ManagementRefusal(why, "Divert '" + failed.getKey() + "' exists and could not be read: "
                            + failed.getValue().getMessage(), failed.getValue());
                }
            }
        }
        diverts.sort(Comparator.comparing(Divert::name));
        return diverts;
    }

    /** Diverts reading from {@code address}. */
    public static List<Divert> from(List<Divert> diverts, String address)
    {
        return diverts.stream().filter(divert -> address.equals(divert.address())).toList();
    }

    /** Diverts sending to {@code address}. */
    public static List<Divert> to(List<Divert> diverts, String address)
    {
        return diverts.stream().filter(divert -> address.equals(divert.forwardingAddress())).toList();
    }

    /** {@code getDivertNames} answers an {@code Object[]} over the management address. */
    private List<String> names(Object result)
    {
        List<String> names = new ArrayList<>();
        if (result instanceof Object[] array)
        {
            for (Object name : array)
            {
                names.add(String.valueOf(name));
            }
        }
        else if (result instanceof Collection<?> collection)
        {
            collection.forEach(name -> names.add(String.valueOf(name)));
        }
        return names;
    }

    private String string(Object value)
    {
        return value == null ? "" : value.toString();
    }
}
