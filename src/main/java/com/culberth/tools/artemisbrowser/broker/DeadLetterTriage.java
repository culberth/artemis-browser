package com.culberth.tools.artemisbrowser.broker;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * A bounded sample of one queue — usually a dead-letter or expiry queue — grouped by where each message came from.
 *
 * <p>
 * What the broker records, verified on 2.55.0 and 2.57.0: a message it moves to another address carries
 * {@code _AMQ_ORIG_ADDRESS}, {@code _AMQ_ORIG_QUEUE}, {@code _AMQ_ORIG_ROUTING_TYPE} and {@code _AMQ_ORIG_MESSAGE_ID},
 * and one it expired also carries {@code _AMQ_ACTUAL_EXPIRY}. Nothing records <em>why</em> a message was dead-lettered:
 * one killed after its delivery attempts, one sent there by an operator and one moved by {@code moveMessages} look the
 * same. So a group says where its messages came from and whether the broker expired them — never a failure reason.
 *
 * <p>
 * The sample is the head of the queue, oldest first, as management {@code browse} pages it: waiting messages only, not
 * in-flight or scheduled ones, and not a random sample. Every share is of the sample, not the queue.
 *
 * @param messageCountAfter the queue's count when sampling ended, or null when it could not be read again
 * @param sampled           distinct messages read, duplicates between pages removed
 * @param reachedEnd        the browse ran out of messages before the sample size was reached
 * @param duplicates        messages seen on two pages, because the queue moved between them
 * @param noOrigin          sampled messages carrying no origin at all: sent there directly, or by something that does
 *                          not record one
 * @param partialOrigin     ones with an origin address but no origin queue
 * @param originsNotListed  origins past {@code maxGroups}, with {@code messagesNotListed} messages between them
 * @param settingsNotRead   origin addresses whose settings were past the read limit
 * @param groupBy           the sample grouped by one property's value, or null when none was asked for
 */
public record DeadLetterTriage(String queueName, String address, String filter, long messageCountBefore,
        Long messageCountAfter, long deliveringCount, long scheduledCount, int requested, int sampled,
        boolean reachedEnd, int duplicates, int pageSize, int maxGroups, int settingsReadLimit, Instant startedAt,
        Instant finishedAt, long managementCalls, List<Origin> origins, int originsNotListed, int messagesNotListed,
        int noOrigin, int partialOrigin, int expired, int settingsNotRead, List<PropertySeen> properties,
        int propertiesNotListed, GroupBy groupBy)
{

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    static String time(Long millis)
    {
        return millis == null ? "" : TIME.format(Instant.ofEpochMilli(millis));
    }

    /** A share of the sample, one decimal place — labelled as such on the page, never as a share of the queue. */
    public String share(int count)
    {
        return sampled == 0 ? "0" : String.format(Locale.ROOT, "%.1f", 100.0 * count / sampled);
    }

    public boolean empty()
    {
        return sampled == 0;
    }

    /** The sample is the whole queue's waiting messages, as far as two readings a moment apart can tell. */
    public boolean wholeQueue()
    {
        return reachedEnd && messageCountAfter != null && messageCountAfter == messageCountBefore;
    }

    /** The queue's count moved while the sample was read, so pages may overlap or skip. */
    public boolean moved()
    {
        return duplicates > 0 || messageCountAfter == null || messageCountAfter != messageCountBefore;
    }

    public String startedText()
    {
        return TIME.format(startedAt);
    }

    public String finishedText()
    {
        return TIME.format(finishedAt);
    }

    public long elapsedMillis()
    {
        return finishedAt.toEpochMilli() - startedAt.toEpochMilli();
    }

    /**
     * One origin: the address and queue a group of messages left.
     *
     * @param address      {@code _AMQ_ORIG_ADDRESS}, or null
     * @param queue        {@code _AMQ_ORIG_QUEUE}, or null
     * @param routingTypes as recorded: anycast, multicast
     * @param expired      how many carry {@code _AMQ_ACTUAL_EXPIRY} — expired by the broker
     * @param examples     up to three message IDs, to open
     * @param queueExists  the origin queue is on the broker now; false when it has gone, null when not asked
     * @param settings     the origin address's settings <em>now</em>, not when the message moved; not collected past
     *                     the read limit
     * @param thisAddress  the sampled queue's address, which the settings are compared with
     */
    public record Origin(String address, String queue, List<String> routingTypes, int count, int expired,
            Long firstSent, Long lastSent, Long firstExpired, Long lastExpired, List<String> protocols,
            List<String> examples, Boolean queueExists, Reading<AddressSettings> settings, String thisAddress)
    {

        public boolean known()
        {
            return address != null || queue != null;
        }

        public String label()
        {
            if (!known())
            {
                return "no origin recorded";
            }
            return (address == null ? "?" : address) + " / " + (queue == null ? "queue not recorded" : queue);
        }

        public String firstSentText()
        {
            return time(firstSent);
        }

        public String lastSentText()
        {
            return time(lastSent);
        }

        public String firstExpiredText()
        {
            return time(firstExpired);
        }

        public String lastExpiredText()
        {
            return time(lastExpired);
        }

        public boolean settingsRead()
        {
            return settings != null && settings.available() && settings.value() != null;
        }

        /** The origin's current settings name this queue's address as their dead-letter address. */
        public boolean deadLettersHere()
        {
            return settingsRead() && thisAddress != null && thisAddress.equals(settings.value().deadLetterAddress());
        }

        /** The origin's current settings name this queue's address as their expiry address. */
        public boolean expiresHere()
        {
            return settingsRead() && thisAddress != null && thisAddress.equals(settings.value().expiryAddress());
        }

        /**
         * What the origin's settings say now, in words. Observed: the settings. Not observed: that they were the same
         * when the message moved, or that the message moved because of them.
         */
        public String settingsText()
        {
            if (!settingsRead())
            {
                return "";
            }
            AddressSettings value = settings.value();
            String dla = value.deadLetterAddress() == null ? "none" : value.deadLetterAddress();
            String expiry = value.expiryAddress() == null ? "none" : value.expiryAddress();
            return "dead-letter address " + dla + ", expiry address " + expiry
                    + (value.maxDeliveryAttempts() == null ? ""
                            : ", " + value.maxDeliveryAttempts() + " delivery attempts");
        }
    }

    /**
     * A property name and how many sampled messages carry it.
     *
     * @param artemis the broker's own bookkeeping ({@code _AMQ_…}, or an AMQP message's {@code extraProperties.} and
     *                {@code x-opt-} annotations) rather than something a producer set
     */
    public record PropertySeen(String name, int count, boolean artemis)
    {
    }

    /**
     * The sample grouped by one property's value.
     *
     * @param notSet          sampled messages without the property — not an empty value, which is a value
     * @param valuesNotListed distinct values past the group limit, with {@code messagesNotListed} messages between them
     */
    public record GroupBy(String property, List<ValueGroup> values, int notSet, int valuesNotListed,
            int messagesNotListed)
    {
    }

    /**
     * One value of the grouped property.
     *
     * @param value     as browse gave it, cut to {@link DeadLetterTriageService#MAX_VALUE_CHARS} when longer
     * @param truncated whether it was cut; values alike up to the cut are counted together
     * @param origins   up to three origin labels among these messages, of {@code originCount}
     */
    public record ValueGroup(String value, boolean truncated, int count, List<String> origins, int originCount,
            List<String> examples)
    {
    }
}
