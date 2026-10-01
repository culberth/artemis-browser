package com.culberth.tools.artemislab.broker;

import com.culberth.tools.artemislab.LabException;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Brokers as Testcontainers containers — the same library and readiness check as Artemis Browser's integration tests.
 *
 * <p>
 * The broker's 61616 is published on 127.0.0.1 at a fixed port rather than Testcontainers' usual random one, so the
 * endpoint a person types into Artemis Browser is stable. Testcontainers' reaper removes the container if the lab
 * process dies, so a lab restart finds its previous broker gone — or, within the reaper's grace period, still running,
 * which {@link #leftovers()} reports.
 */
@Component
class TestcontainersBrokerLauncher implements BrokerLauncher
{

    private static final Logger LOG = LoggerFactory.getLogger(TestcontainersBrokerLauncher.class);
    private static final int BROKER_PORT = 61616;

    /**
     * Broker ids this process launched, recorded before the container is created, so a broker still starting is never
     * offered for removal as someone else's leftover.
     */
    private final Set<String> launchedHere = ConcurrentHashMap.newKeySet();

    @Override
    public Launched launch(String brokerId, String image, int hostPort, String user, String password,
            Duration startupTimeout)
    {
        PortProbe.ensureFree(hostPort);
        launchedHere.add(brokerId);
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(image))
                .withEnv("ARTEMIS_USER", user).withEnv("ARTEMIS_PASSWORD", password).withExposedPorts(BROKER_PORT)
                .withLabel(BROKER_LABEL, brokerId).withCreateContainerCmdModifier(cmd ->
                {
                    HostConfig hostConfig = cmd.getHostConfig() == null ? HostConfig.newHostConfig()
                            : cmd.getHostConfig();
                    cmd.withHostConfig(hostConfig.withPortBindings(new PortBinding(
                            Ports.Binding.bindIpAndPort("127.0.0.1", hostPort), ExposedPort.tcp(BROKER_PORT))));
                })
                // "Server is now active" — not "live", which the log does not say.
                .waitingFor(Wait.forLogMessage(".*Server is now active.*\\n", 1)).withStartupTimeout(startupTimeout);
        try
        {
            container.start();
        }
        catch (RuntimeException e)
        {
            container.stop();
            throw new LabException("The broker did not start from " + image + ": " + rootMessage(e), e);
        }
        int published = container.getMappedPort(BROKER_PORT);
        if (published != hostPort)
        {
            container.stop();
            throw new LabException("Docker published the broker on " + published + ", not the requested " + hostPort
                    + "; stopped it.");
        }
        return new Launched()
        {
            @Override
            public String containerId()
            {
                return container.getContainerId();
            }

            @Override
            public String host()
            {
                return "127.0.0.1";
            }

            @Override
            public int port()
            {
                return published;
            }

            @Override
            public void stop()
            {
                container.stop();
            }

            @Override
            public void restart(Duration startupTimeout)
            {
                // Readiness is read from the container's whole log, so after a restart it is the next occurrence.
                long before = activeLines(container.getLogs());
                container.getDockerClient().restartContainerCmd(container.getContainerId()).exec();
                long deadline = System.nanoTime() + startupTimeout.toNanos();
                while (activeLines(container.getLogs()) <= before)
                {
                    if (System.nanoTime() > deadline)
                    {
                        throw new LabException(
                                "The broker was not active again within " + startupTimeout + " of a restart.");
                    }
                    try
                    {
                        Thread.sleep(250);
                    }
                    catch (InterruptedException e)
                    {
                        Thread.currentThread().interrupt();
                        throw new LabException("Interrupted waiting for the broker to restart.", e);
                    }
                }
            }
        };
    }

    @Override
    public List<Leftover> leftovers()
    {
        try
        {
            DockerClient client = DockerClientFactory.instance().client();
            List<Container> containers = client.listContainersCmd().withShowAll(true)
                    .withLabelFilter(List.of(BROKER_LABEL)).exec();
            return containers.stream().filter(c -> !launchedHere.contains(c.getLabels().get(BROKER_LABEL)))
                    .map(c -> new Leftover(c.getId(), c.getLabels().get(BROKER_LABEL), c.getImage(), c.getStatus()))
                    .toList();
        }
        catch (RuntimeException e)
        {
            LOG.debug("Could not list lab containers", e);
            return List.of();
        }
    }

    @Override
    public void removeLeftover(String containerId)
    {
        boolean listed = leftovers().stream().anyMatch(l -> l.containerId().equals(containerId));
        if (!listed)
        {
            throw new LabException("That container is not a lab leftover; nothing was removed.");
        }
        DockerClientFactory.instance().client().removeContainerCmd(containerId).withForce(true).withRemoveVolumes(true)
                .exec();
    }

    private static long activeLines(String log)
    {
        return log.lines().filter(line -> line.contains("Server is now active")).count();
    }

    private static String rootMessage(Throwable e)
    {
        Throwable root = e;
        while (root.getCause() != null && root.getCause() != root)
        {
            root = root.getCause();
        }
        return root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
    }
}
