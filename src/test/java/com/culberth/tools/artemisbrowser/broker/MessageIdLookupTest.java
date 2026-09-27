package com.culberth.tools.artemisbrowser.broker;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Only an exact ID counts as a lookup: anything that would need Artemis's filter language evaluated to answer against
 * in-flight messages must not be mistaken for one.
 */
class MessageIdLookupTest
{

    private static final String ID = "ID:3be27521-bac0-11f1-8802-00155d348692";

    @ParameterizedTest
    @ValueSource(strings =
    { "AMQUserID = '" + ID + "'", "AMQUserID='" + ID + "'", "  AMQUserID  =  '" + ID + "'  ", ID, "  " + ID + " "
    })
    @DisplayName("the core filter and a bare pasted ID are both exact lookups")
    void recognisesALookup(String input)
    {
        assertEquals(ID, MessageIdLookup.messageId(input));
        assertEquals(ID, MessageIdLookup.messageId(MessageIdLookup.filterFor(input)),
                "what is sent to the broker is still recognised as the same lookup");
    }

    @ParameterizedTest
    @ValueSource(strings =
    { "AMQUserID = '" + ID + "' AND region = 'eu'", "AMQUserID LIKE 'ID:%'", "AMQUserID <> '" + ID + "'",
            "JMSMessageID = '" + ID + "'", "region = 'eu'", "3be27521-bac0-11f1-8802-00155d348692", ""
    })
    @DisplayName("anything else is an ordinary filter, even when it mentions an ID")
    void rejectsEverythingElse(String input)
    {
        assertNull(MessageIdLookup.messageId(input));
    }

    @Test
    @DisplayName("a bare ID reaches the broker as a filter, not as a syntax error; other filters pass through")
    void rewritesOnlyABareId()
    {
        assertEquals("AMQUserID = '" + ID + "'", MessageIdLookup.filterFor(" " + ID + " "));
        assertEquals("region = 'eu'", MessageIdLookup.filterFor("  region = 'eu' "));
        assertEquals("", MessageIdLookup.filterFor(null));
        assertNull(MessageIdLookup.messageId(null));
    }
}
