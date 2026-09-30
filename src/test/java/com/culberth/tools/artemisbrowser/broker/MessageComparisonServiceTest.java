package com.culberth.tools.artemisbrowser.broker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MessageComparisonServiceTest
{

    private final QueueDirectory queues = mock(QueueDirectory.class);
    private final QueueBrowseService browse = mock(QueueBrowseService.class);
    private final MessageComparisonService service = new MessageComparisonService(queues, browse, 100_000, 1_000_000,
            2_000, 5_000);

    @BeforeEach
    void broker()
    {
        given(queues.overview()).willReturn(List.of(queue("orders", "orders", 0, 0), queue("sub", "events", 3, 2)));
    }

    @Test
    @DisplayName("both read: every part compared, each side with its own read time, one queue listing for both")
    void comparesTwo()
    {
        given(browse.detail("orders", "orders", "ID:1")).willReturn(detail("orders", "ID:1", "a"));
        given(browse.detail("orders", "orders", "ID:2")).willReturn(detail("orders", "ID:2", "b"));

        MessageComparison comparison = service.compare("orders", "ID:1", "orders", "ID:2");

        assertThat(comparison.compared()).isTrue();
        assertThat(comparison.sameMessage()).isFalse();
        assertThat(comparison.left().readAtText()).isNotBlank();
        assertThat(comparison.right().readAtText()).isNotBlank();
        assertThat(comparison.headers()).isNotEmpty();
        assertThat(comparison.body().identical()).isFalse();
        verify(queues, times(1)).overview();
    }

    @Test
    @DisplayName("a subscription queue is read by its fully qualified name")
    void readsSubscriptionByFqqn()
    {
        given(browse.detail("sub", "events::sub", "ID:9")).willReturn(detail("sub", "ID:9", "a"));
        given(browse.detail("orders", "orders", "ID:1")).willReturn(detail("orders", "ID:1", "a"));

        MessageComparison comparison = service.compare("sub", "ID:9", "orders", "ID:1");

        assertThat(comparison.compared()).isTrue();
        verify(browse).detail("sub", "events::sub", "ID:9");
    }

    @Test
    @DisplayName("a message gone since it was picked is unavailable, not empty, and in-flight and scheduled are named")
    void goneIsUnavailable()
    {
        given(browse.detail("orders", "orders", "ID:1")).willReturn(detail("orders", "ID:1", "a"));
        given(browse.detail("sub", "events::sub", "ID:gone")).willReturn(null);

        MessageComparison comparison = service.compare("orders", "ID:1", "sub", "ID:gone");

        assertThat(comparison.compared()).isFalse();
        assertThat(comparison.headers()).isNull();
        assertThat(comparison.body()).isNull();
        assertThat(comparison.left().available()).isTrue();
        assertThat(comparison.right().available()).isFalse();
        assertThat(comparison.right().readAtText()).isNotBlank();
        assertThat(comparison.right().unavailable()).contains("not an empty message")
                .contains("3 in flight to a consumer").contains("bodies are never read").contains("2 scheduled");
    }

    @Test
    @DisplayName("a queue the broker does not list is never browsed")
    void missingQueue()
    {
        given(browse.detail("orders", "orders", "ID:1")).willReturn(detail("orders", "ID:1", "a"));

        MessageComparison comparison = service.compare("orders", "ID:1", "nope", "ID:2");

        assertThat(comparison.right().unavailable()).isEqualTo("No queue named 'nope' on this broker.");
        assertThat(comparison.right().readAtText()).isEmpty();
        verify(browse, never()).detail("nope", "nope", "ID:2");
    }

    @Test
    @DisplayName("a failed read is reported on its side and the other side is still read")
    void failedRead()
    {
        given(browse.detail("orders", "orders", "ID:1")).willThrow(new BrokerException("Session is closed"));
        given(browse.detail("orders", "orders", "ID:2")).willReturn(detail("orders", "ID:2", "b"));

        MessageComparison comparison = service.compare("orders", "ID:1", "orders", "ID:2");

        assertThat(comparison.left().unavailable()).isEqualTo("Could not be read: Session is closed");
        assertThat(comparison.right().available()).isTrue();
        assertThat(comparison.compared()).isFalse();
    }

    @Test
    @DisplayName("the same message twice is read twice and marked as such")
    void sameMessage()
    {
        given(browse.detail(anyString(), anyString(), anyString())).willReturn(detail("orders", "ID:1", "a"));

        MessageComparison comparison = service.compare("orders", "ID:1", "orders", "ID:1");

        assertThat(comparison.sameMessage()).isTrue();
        assertThat(comparison.headerDifferences()).isZero();
        verify(browse, times(2)).detail("orders", "orders", "ID:1");
    }

    private static QueueOverview queue(String name, String address, long delivering, long scheduled)
    {
        return new QueueOverview(name, address, "ANYCAST", 5, delivering, scheduled, 0, 5, 0, true, false, false);
    }

    private static MessageDetail detail(String queue, String id, String body)
    {
        return new MessageDetail(queue, id, null, "Text", "queue://" + queue, "2026-09-30 10:00:00", "never", 4, true,
                false, 0, null, false, body, false, Map.of(), Map.of());
    }
}
