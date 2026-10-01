package com.culberth.tools.artemislab.broker;

import java.time.Duration;
import java.util.List;

/** Starts and removes disposable broker containers, and finds ones a previous lab process left behind. */
public interface BrokerLauncher
{

    /** The label every lab container carries, valued with its broker id. Ownership is this label plus a manifest. */
    String BROKER_LABEL = "artemis-lab.broker";

    /**
     * Starts {@code image} with its 61616 published on 127.0.0.1:{@code hostPort}, and waits until it is active.
     *
     * @throws com.culberth.tools.artemislab.LabException if the port is taken or the broker does not become ready
     */
    Launched launch(String brokerId, String image, int hostPort, String user, String password, Duration startupTimeout);

    /** Lab-labelled containers that this process did not launch. Empty, with no error, when Docker is unreachable. */
    List<Leftover> leftovers();

    /** Removes a leftover — only one {@link #leftovers()} currently reports. */
    void removeLeftover(String containerId);

    /** A started container. */
    interface Launched
    {
        String containerId();

        String host();

        int port();

        void stop();
    }

    /** A labelled container from an earlier lab process. */
    record Leftover(String containerId, String brokerId, String image, String status)
    {
    }
}
