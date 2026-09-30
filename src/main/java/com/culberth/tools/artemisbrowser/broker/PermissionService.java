package com.culberth.tools.artemisbrowser.broker;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads the roles the broker grants on an address, and whether it enforces security.
 *
 * <p>
 * {@code broker.getRolesAsJSON(address)} — one call per address, recorded on 2.55.0 and 2.57.0: a JSON array of
 * {@code {"name":role, "send":true, …}}. It resolves the match itself, so it answers for any name, existing or not,
 * with the roles of the most specific security setting. The {@code address.<name>} attribute {@code rolesAsJSON} gives
 * the same for an existing address only; the broker operation is used. {@code securityEnabled} is a broker attribute,
 * Boolean.
 *
 * <p>
 * The same operation is permitted or denied for every address alike, so the first refusal of it stands for the rest and
 * they are not asked — a restricted user's page stays one call long. Addresses past a caller's limit are counted, not
 * read.
 */
@Service
public class PermissionService
{

    private final BrokerSession brokerSession;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PermissionService(BrokerSession brokerSession)
    {
        this.brokerSession = brokerSession;
    }

    public Permissions forAddress(String address)
    {
        return forAddresses(List.of(address), 1);
    }

    /** Roles for each address, up to {@code limit}; each address its own reading. */
    public Permissions forAddresses(Collection<String> addresses, int limit)
    {
        ManagementChannel management = brokerSession.requireManagement();
        Instant started = Instant.now();
        Reading<Boolean> enabled = Reading.attempt(() ->
        {
            Object value = management.attribute(ResourceNames.BROKER, "securityEnabled");
            Boolean flag = ConnectivityService.bool(value);
            if (flag == null)
            {
                throw new BrokerException("'" + value + "' for securityEnabled is not true or false");
            }
            return flag;
        });
        Map<String, Reading<List<RoleGrant>>> byAddress = new LinkedHashMap<>();
        Reading<List<RoleGrant>> refused = null;
        int notRead = 0;
        for (String address : addresses)
        {
            if (byAddress.containsKey(address))
            {
                continue;
            }
            if (byAddress.size() >= limit)
            {
                notRead++;
                continue;
            }
            if (refused != null)
            {
                byAddress.put(address, refused);
                continue;
            }
            Reading<List<RoleGrant>> roles = Reading
                    .attempt(() -> roles(management.invoke(ResourceNames.BROKER, "getRolesAsJSON", address)));
            if (roles.availability() == Availability.DENIED || roles.availability() == Availability.UNSUPPORTED)
            {
                refused = roles;
            }
            byAddress.put(address, roles);
        }
        return new Permissions(enabled, byAddress, notRead, started);
    }

    List<RoleGrant> roles(Object result)
    {
        if (result == null || result.toString().isBlank())
        {
            return List.of();
        }
        JsonNode root;
        try
        {
            root = objectMapper.readTree(result.toString());
        }
        catch (Exception e)
        {
            throw new BrokerException("Could not read the broker's roles: " + e.getMessage(), e);
        }
        if (!root.isArray())
        {
            throw new BrokerException("expected a JSON array of roles, got: " + result);
        }
        List<RoleGrant> grants = new ArrayList<>();
        for (JsonNode node : root)
        {
            Map<String, Boolean> permissions = new LinkedHashMap<>();
            for (RoleGrant.Permission permission : RoleGrant.PERMISSIONS)
            {
                JsonNode value = node.get(permission.key());
                if (value != null && value.isBoolean())
                {
                    permissions.put(permission.key(), value.asBoolean());
                }
            }
            JsonNode name = node.get("name");
            grants.add(new RoleGrant(name == null || name.isNull() ? "" : name.asString(), permissions));
        }
        return grants;
    }
}
