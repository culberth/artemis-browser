package com.culberth.tools.artemisbrowser.broker;

import java.util.List;

/**
 * What a browse returned.
 *
 * @param limit    how many messages were asked for
 * @param more     true if the queue held more than {@code limit}; the browse stopped early
 */
public record BrowseResult(String queueName, int limit, boolean more, List<MessageSummary> messages) {}
