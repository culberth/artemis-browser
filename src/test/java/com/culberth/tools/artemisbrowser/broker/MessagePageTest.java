package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Off-by-one errors in paging are invisible until someone notices a missing message. */
class MessagePageTest
{

    @Test
    @DisplayName("a partial last page still counts as a page")
    void roundsPartialPageUp()
    {
        assertEquals(24, page(1, 50, 1200).totalPages());
        assertEquals(25, page(1, 50, 1201).totalPages());
    }

    @Test
    @DisplayName("an exact multiple does not produce a trailing empty page")
    void exactMultipleHasNoExtraPage()
    {
        assertEquals(24, page(1, 50, 1200).totalPages());
        assertFalse(page(24, 50, 1200).hasNext());
    }

    @Test
    @DisplayName("an empty queue is one page, not zero")
    void emptyQueueIsOnePage()
    {
        MessagePage empty = new MessagePage("q", "", 1, 50, 0, List.of());

        assertEquals(1, empty.totalPages());
        assertFalse(empty.hasNext());
        assertFalse(empty.hasPrevious());
    }

    @Test
    @DisplayName("next and previous stay inside the range")
    void navigationIsClamped()
    {
        assertEquals(1, page(1, 50, 1200).previousPage());
        assertEquals(24, page(24, 50, 1200).nextPage());
        assertTrue(page(2, 50, 1200).hasPrevious());
        assertTrue(page(2, 50, 1200).hasNext());
    }

    @Test
    @DisplayName("the showing-N-to-M range is 1-based and reflects the actual rows returned")
    void reportsRange()
    {
        MessagePage third = page(3, 50, 1200);

        assertEquals(101, third.firstIndex());
        assertEquals(150, third.lastIndex());
    }

    @Test
    @DisplayName("a short final page reports its real last index, not the page boundary")
    void shortFinalPageRange()
    {
        MessagePage last = new MessagePage("q", "", 25, 50, 1201, rows(1));

        assertEquals(1201, last.firstIndex());
        assertEquals(1201, last.lastIndex());
    }

    @Test
    @DisplayName("an empty page reports no range rather than 1-0")
    void emptyPageHasNoRange()
    {
        MessagePage none = new MessagePage("q", "AMQPriority > 9", 1, 50, 0, List.of());

        assertEquals(0, none.firstIndex());
        assertEquals(0, none.lastIndex());
    }

    private MessagePage page(int page, int size, long total)
    {
        return new MessagePage("q", "", page, size, total, rows(size));
    }

    private List<MessageSummary> rows(int count)
    {
        return Collections.nCopies(count,
                new MessageSummary(1, "ID:1", "1", "Text", null, "", 4, true, false, 0, "CORE", false, "", false));
    }
}
