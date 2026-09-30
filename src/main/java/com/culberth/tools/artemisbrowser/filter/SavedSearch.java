package com.culberth.tools.artemisbrowser.filter;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * A named investigation: a filter and where to run it. Nothing else.
 *
 * <p>
 * There is no password, no message body and no result here, and there must never be: a saved search is a way back to a
 * question, not a record of an answer. {@code savedFrom} is the broker's {@code host:port} when it was saved — no user
 * name — so that running it later against a different broker can be pointed out rather than go unnoticed.
 */
public record SavedSearch(String id, String name, String filter, Scope scope, String target, boolean internal,
        String savedFrom, Instant created, Instant updated)
{

    private static final DateTimeFormatter SHOWN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    /** Where a filter is run. Each is one page this tool already had; saving changes nothing about how it searches. */
    public enum Scope
    {
        /** {@code /search}: every queue, optionally internal ones too. */
        ALL_QUEUES("every queue"),
        /** {@code /queues?name=}: one queue, paged. */
        QUEUE("queue"),
        /** {@code /address?name=&find=}: every subscription on one address. */
        ADDRESS("address");

        private final String label;

        Scope(String label)
        {
            this.label = label;
        }

        public String label()
        {
            return label;
        }
    }

    /** A hand-edited or older file may leave these out; they read as blank, never as null. */
    public SavedSearch
    {
        target = target == null ? "" : target;
        savedFrom = savedFrom == null ? "" : savedFrom;
    }

    public SavedSearch withName(String newName, Instant at)
    {
        return new SavedSearch(id, newName, filter, scope, target, internal, savedFrom, created, at);
    }

    /** "every queue", "queue orders", "address events". */
    public String describeScope()
    {
        return scope == Scope.ALL_QUEUES ? scope.label() + (internal ? ", internal ones included" : "")
                : scope.label() + " " + target;
    }

    public String createdText()
    {
        return created == null ? "" : SHOWN.format(created);
    }

    public String updatedText()
    {
        return updated == null ? "" : SHOWN.format(updated);
    }
}
