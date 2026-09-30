package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import org.apache.activemq.artemis.api.core.management.ResourceNames;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Roles per address, parsed from {@code getRolesAsJSON} as recorded against 2.55.0 and 2.57.0. */
class PermissionServiceTest
{

    /** The image's default {@code #} setting, as the broker reports it for any ordinary address. */
    private static final String DEFAULT_ROLES = "[{\"name\":\"amq\",\"send\":true,\"consume\":true,"
            + "\"createDurableQueue\":true,\"deleteDurableQueue\":true,\"createNonDurableQueue\":true,"
            + "\"deleteNonDurableQueue\":true,\"manage\":false,\"browse\":true,\"createAddress\":true,"
            + "\"deleteAddress\":true,\"view\":false,\"edit\":false}]";

    private BrokerSession brokerSession;
    private ManagementChannel management;

    @BeforeEach
    void mocks()
    {
        brokerSession = mock(BrokerSession.class);
        management = mock(ManagementChannel.class);
        given(brokerSession.requireManagement()).willReturn(management);
        given(management.attribute(ResourceNames.BROKER, "securityEnabled")).willReturn(true);
        given(management.invoke(eq(ResourceNames.BROKER), eq("getRolesAsJSON"), anyString())).willReturn(DEFAULT_ROLES);
    }

    @Test
    @DisplayName("each role's permissions are read by name, a false kept as false")
    void readsRoles()
    {
        Permissions permissions = new PermissionService(brokerSession).forAddress("orders");

        RoleGrant amq = permissions.of("orders").value().get(0);
        assertEquals("amq", amq.role());
        assertEquals(Boolean.TRUE, amq.allows("send"));
        assertEquals(Boolean.FALSE, amq.allows("manage"));
        assertEquals(RoleGrant.PERMISSIONS.size(), amq.permissions().size());
        assertTrue(permissions.enforced());
        assertEquals(List.of("amq"), permissions.rolesThatMay("orders", "consume"));
        assertEquals("no role", permissions.rolesThatMayText("orders", "manage"));
    }

    @Test
    @DisplayName("a permission an older broker does not report is absent, not false")
    void keepsUnreportedPermissionsAbsent()
    {
        given(management.invoke(ResourceNames.BROKER, "getRolesAsJSON", "orders"))
                .willReturn("[{\"name\":\"amq\",\"send\":true,\"consume\":false}]");

        RoleGrant amq = new PermissionService(brokerSession).forAddress("orders").of("orders").value().get(0);

        assertEquals(Boolean.FALSE, amq.allows("consume"));
        assertNull(amq.allows("view"));
        assertFalse(amq.reported("view"));
    }

    @Test
    @DisplayName("a refusal stands for every address, which are then not asked")
    void stopsAtTheFirstRefusal()
    {
        given(management.invoke(eq(ResourceNames.BROKER), eq("getRolesAsJSON"), anyString()))
                .willThrow(new ManagementRefusal(Availability.DENIED, "AMQ229032 getRolesAsJSON"));

        Permissions permissions = new PermissionService(brokerSession).forAddresses(List.of("a", "b", "c"), 10);

        assertEquals(Availability.DENIED, permissions.of("c").availability());
        verify(management, times(1)).invoke(eq(ResourceNames.BROKER), eq("getRolesAsJSON"), anyString());
    }

    @Test
    @DisplayName("addresses past the limit are counted, not read")
    void countsWhatItDoesNotRead()
    {
        Permissions permissions = new PermissionService(brokerSession).forAddresses(List.of("a", "b", "c"), 2);

        assertEquals(2, permissions.byAddress().size());
        assertEquals(1, permissions.notRead());
        assertEquals(Availability.NOT_COLLECTED, permissions.of("c").availability());
    }

    @Test
    @DisplayName("security switched off is reported, and an unreadable flag is never taken as off")
    void readsWhetherSecurityIsEnforced()
    {
        given(management.attribute(ResourceNames.BROKER, "securityEnabled")).willReturn(false);
        assertFalse(new PermissionService(brokerSession).forAddress("orders").enforced());

        given(management.attribute(ResourceNames.BROKER, "securityEnabled"))
                .willThrow(new ManagementRefusal(Availability.UNAVAILABLE, "Problem while retrieving attribute"));
        Permissions unknown = new PermissionService(brokerSession).forAddress("orders");
        assertEquals(Availability.UNAVAILABLE, unknown.securityEnabled().availability());
        assertTrue(unknown.enforced());
    }

    @Test
    @DisplayName("a reply that is not a list of roles fails that address, not the page")
    void failsOnMalformed()
    {
        given(management.invoke(ResourceNames.BROKER, "getRolesAsJSON", "orders")).willReturn("{\"name\":\"amq\"}");

        assertEquals(Availability.FAILED,
                new PermissionService(brokerSession).forAddress("orders").of("orders").availability());
    }
}
