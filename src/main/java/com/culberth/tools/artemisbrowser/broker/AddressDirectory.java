package com.culberth.tools.artemisbrowser.broker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the broker's addresses, each with the queues bound to it.
 *
 * <p>
 * The queues come from {@link QueueDirectory}'s existing listing rather than a per-address {@code getQueueNames} call —
 * the queue overview already carries each queue's address, so grouping what we have costs nothing, where asking per
 * address would be one round trip per address.
 */
@Service
public class AddressDirectory
{

    private static final int LIST_PAGE_SIZE = 200;

    private final BrokerSession brokerSession;
    private final QueueDirectory queueDirectory;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AddressDirectory(BrokerSession brokerSession, QueueDirectory queueDirectory)
    {
        this.brokerSession = brokerSession;
        this.queueDirectory = queueDirectory;
    }

    public List<AddressOverview> overview()
    {
        ManagementChannel management = brokerSession.requireManagement();
        Map<String, List<QueueOverview>> queuesByAddress = queueDirectory.overview().stream()
                .collect(Collectors.groupingBy(QueueOverview::address));

        List<AddressOverview> addresses = new ArrayList<>();
        for (int page = 1;; page++)
        {
            JsonNode data = listAddresses(management, page);
            if (data == null || data.isEmpty())
            {
                break;
            }
            for (JsonNode node : data)
            {
                String name = text(node, "name");
                if (name == null)
                {
                    continue;
                }
                List<QueueOverview> queues = queuesByAddress.getOrDefault(name, List.of());
                // The address behind our own management reply queue is this tool's plumbing, and
                // listing it invites someone to go looking at a queue that vanishes on disconnect.
                if (queues.isEmpty() && flag(node, "temporary"))
                {
                    continue;
                }
                addresses.add(toOverview(node, name, queues));
            }
            if (data.size() < LIST_PAGE_SIZE)
            {
                break;
            }
        }
        addresses.sort(Comparator.comparing(address -> address.name().toLowerCase()));
        return addresses;
    }

    public AddressOverview find(String name)
    {
        for (AddressOverview address : overview())
        {
            if (address.name().equals(name))
            {
                return address;
            }
        }
        return null;
    }

    /**
     * The settings the broker resolved for {@code name}. Every value is kept as text: numbers arrive bare and booleans
     * as JSON booleans here, and nothing on the page does arithmetic with them.
     */
    public AddressSettings settings(String name)
    {
        Object result = brokerSession.requireManagement().invoke(ResourceNames.BROKER, "getAddressSettingsAsJSON",
                name);
        if (result == null)
        {
            return AddressSettings.of(Map.of());
        }
        try
        {
            JsonNode root = objectMapper.readTree(result.toString());
            Map<String, String> values = new java.util.LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> field : root.properties())
            {
                if (!field.getValue().isNull())
                {
                    values.put(field.getKey(), field.getValue().asText());
                }
            }
            return AddressSettings.of(values);
        }
        catch (Exception e)
        {
            throw new BrokerException("Could not read the settings for '" + name + "': " + e.getMessage(), e);
        }
    }

    private JsonNode listAddresses(ManagementChannel management, int page)
    {
        Object result = management.invoke(ResourceNames.BROKER, "listAddresses", "", page, LIST_PAGE_SIZE);
        if (result == null)
        {
            return null;
        }
        try
        {
            JsonNode data = objectMapper.readTree(result.toString()).get("data");
            return data == null || !data.isArray() ? null : data;
        }
        catch (Exception e)
        {
            throw new BrokerException("Could not read the broker's address list: " + e.getMessage(), e);
        }
    }

    private AddressOverview toOverview(JsonNode node, String name, List<QueueOverview> queues)
    {
        return new AddressOverview(name, routingTypes(node), number(node, "messageCount"), reading(node, "addressSize"),
                number(node, "routedMessageCount"), number(node, "unroutedMessageCount"), flag(node, "paging"),
                flag(node, "internal"), flag(node, "temporary"), queues, reading(node, "addressLimitPercent"),
                reading(node, "numberOfPages"));
    }

    /**
     * Whether an operator blocked this address through management — the one pressure fact the listing leaves out, so
     * one round trip per address. Only an explicit block sets it: an address blocked by its BLOCK policy reads false.
     */
    public Reading<Boolean> blockedViaManagement(String name)
    {
        Reading<Object> raw = Reading.attempt(() -> brokerSession.requireManagement()
                .attribute(ResourceNames.ADDRESS + name, "blockedViaManagement"));
        if (!raw.available())
        {
            return raw.absent();
        }
        if (raw.value() instanceof Boolean flag)
        {
            return Reading.of(flag);
        }
        String text = raw.value() == null ? "" : raw.value().toString().trim();
        if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false"))
        {
            return Reading.of(Boolean.parseBoolean(text));
        }
        return Reading.failed("'" + raw.value() + "' is not true or false");
    }

    /**
     * A counter the listing may or may not carry. Absent means this broker's listing does not have it, and a value that
     * is not a number could not be read — neither is a zero, since these feed a limit check.
     */
    static Reading<Long> reading(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        if (value == null || value.isNull())
        {
            return Reading.missing(Availability.UNSUPPORTED, "'" + field + "' is not in this broker's address listing");
        }
        try
        {
            return Reading.of(Long.parseLong(value.asText().trim()));
        }
        catch (NumberFormatException e)
        {
            return Reading.failed("'" + value.asText() + "' is not a number");
        }
    }

    /**
     * listAddresses returns routingTypes as a JSON array encoded inside a JSON string —
     * {@code "routingTypes":"[\"ANYCAST\"]"}. Rendering that raw puts brackets and escaped quotes on the page, so it is
     * unwrapped to a plain {@code ANYCAST, MULTICAST}.
     */
    private String routingTypes(JsonNode node)
    {
        String raw = text(node, "routingTypes");
        if (raw == null || raw.isBlank())
        {
            return "";
        }
        try
        {
            JsonNode parsed = objectMapper.readTree(raw);
            if (parsed.isArray())
            {
                List<String> types = new ArrayList<>();
                parsed.forEach(entry -> types.add(entry.asText()));
                return String.join(", ", types);
            }
        }
        catch (Exception ignored)
        {
            // Fall through to the cleanup below; an unreadable value is not worth failing a page.
        }
        return raw.replaceAll("[\\[\\]\"\\\\]", "");
    }

    private String text(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private long number(JsonNode node, String field)
    {
        String value = text(node, field);
        if (value == null || value.isBlank())
        {
            return 0L;
        }
        try
        {
            return Long.parseLong(value.trim());
        }
        catch (NumberFormatException e)
        {
            return 0L;
        }
    }

    private boolean flag(JsonNode node, String field)
    {
        return Boolean.parseBoolean(text(node, field));
    }
}
