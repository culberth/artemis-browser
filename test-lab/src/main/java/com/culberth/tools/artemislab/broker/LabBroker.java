package com.culberth.tools.artemislab.broker;

import java.time.Instant;

/**
 * A broker the lab started and has identified. {@code nodeId} is its identity: every connection that writes is checked
 * against it first.
 *
 * @param brokerId        the lab's own id, also the container's {@code artemis-lab.broker} label
 * @param image           the pinned image it was started from
 * @param containerId     the Docker container
 * @param host            the host address clients use (always loopback)
 * @param port            the host port published for the broker's 61616
 * @param user            the admin user (the password is deliberately not here)
 * @param reportedVersion what the broker says its version is
 * @param nodeId          what the broker says its node id is
 * @param startedAt       when it became ready
 */
public record LabBroker(String brokerId, String image, String containerId, String host, int port, String user,
        String reportedVersion, String nodeId, Instant startedAt)
{

    /** For Artemis Browser's connect form and the manifest; carries no credentials. */
    public String endpoint()
    {
        return host + ":" + port;
    }
}
