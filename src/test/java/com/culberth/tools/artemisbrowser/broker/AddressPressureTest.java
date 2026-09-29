package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.culberth.tools.artemisbrowser.broker.AddressPressure.State;
import com.culberth.tools.artemisbrowser.broker.AddressSettings.FullPolicy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Built from what 2.55.0 and 2.57.0 reported for five addresses filled past a 20KB limit, one per policy — recorded in
 * {@code .claude/memory.md}. The point of each case is a percentage or flag that means something different from what it
 * looks like.
 */
class AddressPressureTest
{

    @Test
    @DisplayName("PAGE over its limit and writing pages is paging, not a problem")
    void pageIsPaging()
    {
        AddressPressure pressure = pressure(address(40, 21679, 108, true, 9), settings("PAGE", "20000"));

        assertEquals(State.PAGING, pressure.state());
        assertFalse(pressure.concerning());
        assertEquals("108%", pressure.utilizationText());
    }

    @Test
    @DisplayName("BLOCK at 418% with paging false is at its limit — the percentage, not the flag, shows it")
    void blockIsAtLimitWithoutThePagingFlag()
    {
        AddressPressure pressure = pressure(address(27, 83781, 418, false, 0), settings("BLOCK", "20000"));

        assertEquals(State.AT_LIMIT, pressure.state());
        assertTrue(pressure.concerning());
    }

    @Test
    @DisplayName("FAIL and DROP full report paging with no pages, and are at their limit")
    void failAndDropAreFull()
    {
        assertEquals(State.AT_LIMIT, pressure(address(7, 21679, 108, true, 0), settings("FAIL", "20000")).state());
        assertEquals(State.AT_LIMIT, pressure(address(7, 21679, 108, true, 0), settings("DROP", "20000")).state());
    }

    @Test
    @DisplayName("a limit by message count is computed, since the broker's percentage counts bytes and reads 0")
    void messageLimitIsComputed()
    {
        Map<String, String> values = base("FAIL");
        values.put("maxSizeBytes", "-1");
        values.put("maxSizeMessages", "10");
        AddressPressure pressure = pressure(address(10, 30970, 0, true, 0), AddressSettings.of(values));

        assertEquals(100L, pressure.utilization().value());
        assertEquals(State.AT_LIMIT, pressure.state());
    }

    @Test
    @DisplayName("near a harmful limit is its own state")
    void nearLimit()
    {
        assertEquals(State.NEAR_LIMIT, pressure(address(5, 17000, 85, false, 0), settings("BLOCK", "20000")).state());
    }

    @Test
    @DisplayName("an address with no limit of its own has no utilization, and its broker 0 is not shown as 0%")
    void unlimitedIsNotZeroPercent()
    {
        AddressPressure pressure = pressure(address(40, 123640, 0, false, 0), settings("PAGE", "-1"));

        assertEquals(Availability.NOT_COLLECTED, pressure.utilization().availability());
        assertNull(pressure.utilizationText());
        assertEquals("unlimited", pressure.byteLimitText());
        assertEquals(State.NORMAL, pressure.state());
    }

    @Test
    @DisplayName("an operator's block outranks everything, and is observed")
    void blockedOutranksEverything()
    {
        AddressPressure pressure = new AddressPressure(address(40, 21679, 108, true, 9),
                Reading.of(settings("PAGE", "20000")), Reading.of(true), null);

        assertEquals(State.BLOCKED, pressure.state());
    }

    @Test
    @DisplayName("paging near a page limit is flagged, with the page-full policy's consequence")
    void nearPageLimit()
    {
        Map<String, String> values = base("PAGE");
        values.put("pageLimitMessages", "30");
        values.put("pageFullMessagePolicy", "FAIL");
        AddressPressure pressure = pressure(address(37, 21763, 108, true, 8), AddressSettings.of(values));

        assertEquals(State.NEAR_PAGE_LIMIT, pressure.state());
        assertEquals(FullPolicy.FAIL, pressure.pagePolicy());
        assertEquals("30 messages", pressure.pageLimitText());
    }

    @Test
    @DisplayName("settings that could not be read fall back to what the listing proves, and nothing more")
    void unreadSettings()
    {
        AddressPressure pressure = new AddressPressure(address(7, 21679, 108, true, 0),
                Reading.missing(Availability.DENIED, "AMQ229032"), Reading.of(false), null);

        assertEquals(108L, pressure.utilization().value());
        assertNull(pressure.policy());
        assertEquals(State.PAGING, pressure.state(), "without a policy nothing is claimed about a sender");
    }

    @Test
    @DisplayName("the global share is this address against global-max-size")
    void globalShare()
    {
        BrokerHealth health = BrokerHealth.of("2.57.0", "1m", "STARTED", "n", 1, 1, 1, 325191, 0, 10, 90);
        AddressPressure pressure = new AddressPressure(address(40, 1L << 20, 0, false, 0),
                Reading.of(settings("PAGE", "-1")), Reading.of(false), health);

        assertEquals("0.10%", pressure.globalShareText());
        assertEquals("317.6 KB of 1.0 GB", pressure.globalText());
    }

    @Test
    @DisplayName("limits keep not set, unlimited and a value apart")
    void limitStates()
    {
        AddressSettings settings = AddressSettings.of(Map.of("maxSizeBytes", "-1", "pageLimitMessages", "30"));

        assertTrue(settings.maxSizeBytesLimit().unlimited());
        assertTrue(settings.maxMessagesLimit().notSet());
        assertTrue(settings.pageLimitMessages().limited());
        assertEquals(30, settings.pageLimitMessages().value());
        assertTrue(settings.hasPageLimit());
        assertNull(settings.pageFullPolicy(), "absent until configured, as the broker reports it");
        assertNull(AddressSettings.of(Map.of("addressFullMessagePolicy", "SOMETHING")).addressFullPolicy());
    }

    private static AddressPressure pressure(AddressOverview address, AddressSettings settings)
    {
        return new AddressPressure(address, Reading.of(settings), Reading.of(false), null);
    }

    private static AddressOverview address(long messages, long size, long limitPercent, boolean paging, long pages)
    {
        return new AddressOverview("probe", "ANYCAST", messages, size, messages, 0, paging, false, false, List.of())
                .withStorage(limitPercent, pages);
    }

    private static AddressSettings settings(String policy, String maxSizeBytes)
    {
        Map<String, String> values = base(policy);
        values.put("maxSizeBytes", maxSizeBytes);
        return AddressSettings.of(values);
    }

    /** The keys a stock broker returns, as recorded; pageFullMessagePolicy is absent unless set. */
    private static Map<String, String> base(String policy)
    {
        Map<String, String> values = new HashMap<>();
        values.put("addressFullMessagePolicy", policy);
        values.put("maxSizeBytes", "20000");
        values.put("maxSizeMessages", "-1");
        values.put("pageSizeBytes", "10000");
        values.put("pageLimitBytes", "-1");
        values.put("pageLimitMessages", "-1");
        return values;
    }
}
