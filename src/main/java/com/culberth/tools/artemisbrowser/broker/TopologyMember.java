package com.culberth.tools.artemisbrowser.broker;

/**
 * One node in the cluster topology this broker reports, from {@code listNetworkTopology}.
 *
 * <p>
 * This broker's view, not the node's own: nothing here connects to it. The topology includes this broker itself, and a
 * backup stays listed after it stops — seen still there 70 seconds after its container was stopped, while the primary's
 * {@code replicaSync} had gone false within eight. So a {@code backup} here says one was announced, not that it is up.
 *
 * @param primary {@code host:port} the node's primary announced; the reply also carries it as {@code live}
 * @param backup  {@code host:port} of an announced backup, or empty
 * @param self    this broker's own entry
 */
public record TopologyMember(String nodeId, String primary, String backup, boolean self)
{

    public boolean hasBackup()
    {
        return backup != null && !backup.isBlank();
    }
}
