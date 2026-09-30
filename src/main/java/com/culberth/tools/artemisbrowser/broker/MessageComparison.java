package com.culberth.tools.artemisbrowser.broker;

import java.util.List;

/**
 * Two messages side by side: what each one is, when it was read, and what differs between them.
 *
 * <p>
 * Each message is read on its own, one after the other, through the same JMS browser as the message page — so each side
 * is an observation at its own read time, not two readings at one instant. A side the broker no longer returned is
 * {@link Side#unavailable() unavailable}, never an empty message, and then nothing is compared.
 *
 * @param headers     differences in JMS headers, null when either side is unavailable
 * @param properties  differences in properties by name, null when either side is unavailable
 * @param body        the body comparison, null when either side is unavailable
 * @param sameMessage both sides name the same message on the same queue
 */
public record MessageComparison(Side left, Side right, List<Field> headers, List<Field> properties, Body body,
        boolean sameMessage)
{

    /** Whether both messages were read, so that there is anything to compare. */
    public boolean compared()
    {
        return headers != null;
    }

    public long headerDifferences()
    {
        return headers == null ? 0 : headers.stream().filter(field -> field.change() != Change.SAME).count();
    }

    public long propertyDifferences()
    {
        return properties == null ? 0 : properties.stream().filter(field -> field.change() != Change.SAME).count();
    }

    /**
     * One message as selected and as read.
     *
     * @param readAtText  when the read finished, in this server's zone; blank when it was never attempted
     * @param message     the message, null when it could not be read
     * @param unavailable why it could not be read, null when it was
     */
    public record Side(String queueName, String messageId, String readAtText, MessageDetail message, String unavailable)
    {

        public boolean available()
        {
            return message != null;
        }
    }

    /** How a field, property or line compares between the two sides. */
    public enum Change
    {
        SAME, DIFFERENT, ONLY_LEFT, ONLY_RIGHT;

        public String label()
        {
            return switch (this)
            {
                case SAME -> "same";
                case DIFFERENT -> "differs";
                case ONLY_LEFT -> "only on the left";
                case ONLY_RIGHT -> "only on the right";
            };
        }
    }

    /**
     * One named value on each side. A null value is absent — the message does not carry it at all — which is shown
     * differently from an empty one.
     *
     * @param leftType  the value's type where it matters (a property's Java type, a JSON value's kind), else null
     * @param rightType likewise for the right
     */
    public record Field(String name, String left, String leftType, String right, String rightType, Change change)
    {

        public String leftShown()
        {
            return MessageComparer.shown(left);
        }

        public String rightShown()
        {
            return MessageComparer.shown(right);
        }

        public String nameShown()
        {
            return MessageComparer.visible(name);
        }
    }

    /** One line of a text body diff; a {@code skip} line stands for a run of unchanged lines not drawn. */
    public record Line(String op, int leftNumber, int rightNumber, String text, int skipped)
    {

        public String textShown()
        {
            return MessageComparer.visible(text);
        }
    }

    /** How the bodies were compared, if they were. */
    public enum BodyMode
    {
        TEXT, JSON, NOT_COMPARED
    }

    /**
     * The body comparison.
     *
     * @param identical   the compared text is identical — which, when {@code limitations} is not empty, says nothing
     *                    about the part that was not compared
     * @param explanation what was compared, or why nothing was
     * @param limitations each way this comparison falls short of the whole bodies: truncation, alignment abandoned,
     *                    rows not drawn
     * @param lines       the text diff, for {@link BodyMode#TEXT}
     * @param paths       differing JSON values by path, for {@link BodyMode#JSON}; unchanged ones are counted only
     */
    public record Body(BodyMode mode, boolean identical, String explanation, List<String> limitations, List<Line> lines,
            List<Field> paths, int unchangedPaths)
    {
    }
}
