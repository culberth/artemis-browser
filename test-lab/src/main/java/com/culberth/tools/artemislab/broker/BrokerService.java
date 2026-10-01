package com.culberth.tools.artemislab.broker;

import com.culberth.tools.artemislab.LabException;
import com.culberth.tools.artemislab.LabLimits;
import jakarta.annotation.PreDestroy;
import jakarta.jms.Connection;
import jakarta.jms.JMSException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.springframework.stereotype.Service;

/**
 * The one disposable broker the lab owns at a time: provisioning, identity and teardown.
 *
 * <p>
 * Provisioning records the broker's node id only after two connections held open together report the same one — the
 * same check Artemis Browser's integration fixture makes before seeding. From then on that node id is the broker's
 * identity, and {@link #guard(LabBroker)} checks every new connection against it.
 */
@Service
public class BrokerService
{

    private final BrokerLauncher launcher;
    private final LabBrokerProperties properties;
    private final LabLimits limits;

    private volatile LabBroker current;
    private BrokerLauncher.Launched launched;
    /**
     * One factory per broker, kept open for its life: closing an {@code ActiveMQConnectionFactory} closes every
     * connection it made, which is what stopping the broker wants and nothing else does.
     */
    private ActiveMQConnectionFactory factory;
    /**
     * The same broker with {@code consumerWindowSize=0}, for consumers that must hold exactly what they received: with
     * the default 1MB window the client buffers ahead, and every buffered message counts as delivering too.
     */
    private ActiveMQConnectionFactory holdingFactory;

    public BrokerService(BrokerLauncher launcher, LabBrokerProperties properties, LabLimits limits)
    {
        this.launcher = launcher;
        this.properties = properties;
        this.limits = limits;
    }

    public Optional<LabBroker> current()
    {
        return Optional.ofNullable(current);
    }

    public List<String> supportedImages()
    {
        return properties.images();
    }

    public LabBrokerProperties properties()
    {
        return properties;
    }

    /** Starts a broker from a supported image and identifies it. Refused while one is already running. */
    public synchronized LabBroker provision(String image)
    {
        if (current != null)
        {
            throw new LabException("A lab broker is already running (" + current.brokerId() + "). Stop it first.");
        }
        if (!properties.supports(image))
        {
            throw new LabException("Not a supported image: " + image + ". Choose one of " + properties.images() + ".");
        }
        String brokerId = "b" + HexFormat.of().toHexDigits(ThreadLocalRandom.current().nextInt());
        BrokerLauncher.Launched started = launcher.launch(brokerId, image, properties.port(), properties.user(),
                properties.password(), properties.startupTimeout());
        ActiveMQConnectionFactory startedFactory = new ActiveMQConnectionFactory(url(started.host(), started.port()));
        try
        {
            Identity identity = identify(startedFactory);
            LabBroker broker = new LabBroker(brokerId, image, started.containerId(), started.host(), started.port(),
                    properties.user(), identity.version(), identity.nodeId(), Instant.now());
            this.launched = started;
            this.factory = startedFactory;
            this.holdingFactory = new ActiveMQConnectionFactory(
                    url(started.host(), started.port()) + "&consumerWindowSize=0");
            this.current = broker;
            return broker;
        }
        catch (JMSException | RuntimeException e)
        {
            startedFactory.close();
            started.stop();
            throw e instanceof LabException lab ? lab
                    : new LabException("The broker started but could not be identified: " + e.getMessage(), e);
        }
    }

    /** Stops the broker and discards everything on it. Closing the factory first releases any held deliveries. */
    public synchronized void stop()
    {
        LabBroker stopping = current;
        current = null;
        try
        {
            closeFactories();
        }
        finally
        {
            if (launched != null)
            {
                launched.stop();
            }
            launched = null;
        }
        if (stopping == null)
        {
            throw new LabException("No lab broker is running.");
        }
    }

    /**
     * A guard for the given broker, which must still be the current one: a run recorded against a broker that has since
     * been replaced is refused here, not reconnected to its successor.
     */
    public synchronized TargetGuard guard(LabBroker broker)
    {
        return guard(broker, false);
    }

    /** As {@link #guard(LabBroker)}; {@code holding} for consumers that must receive exactly what they ask for. */
    public synchronized TargetGuard guard(LabBroker broker, boolean holding)
    {
        LabBroker now = current;
        if (now == null || !now.brokerId().equals(broker.brokerId()))
        {
            throw new LabException("Broker " + broker.brokerId() + " is no longer the lab's broker; nothing was sent.");
        }
        return new TargetGuard(holding ? holdingFactory : factory, properties.user(), properties.password(),
                now.nodeId(), TargetGuard.managementReader(limits.operationTimeout()));
    }

    /**
     * Restarts the broker's container and checks it comes back as the same broker. Closing the factories first drops
     * every lab connection, so the caller stops the workers before this. The journal lives in the container, so durable
     * messages and the node id survive; non-durable ones and counters that are not reloaded do not. A different node id
     * afterwards is refused: the broker is stopped rather than adopted.
     */
    public synchronized LabBroker restart()
    {
        LabBroker broker = current;
        if (broker == null)
        {
            throw new LabException("No lab broker is running.");
        }
        closeFactories();
        launched.restart(properties.startupTimeout());
        ActiveMQConnectionFactory restarted = new ActiveMQConnectionFactory(url(broker.host(), broker.port()));
        Identity identity;
        try
        {
            identity = identify(restarted);
        }
        catch (JMSException | RuntimeException e)
        {
            restarted.close();
            stop();
            throw new LabException(
                    "The broker did not come back identifiable after a restart; stopped it: " + e.getMessage(), e);
        }
        if (!identity.nodeId().equals(broker.nodeId()))
        {
            restarted.close();
            stop();
            throw new LabException("After the restart the broker reported node " + identity.nodeId() + ", not "
                    + broker.nodeId() + ". Not adopted; stopped it.");
        }
        factory = restarted;
        holdingFactory = new ActiveMQConnectionFactory(url(broker.host(), broker.port()) + "&consumerWindowSize=0");
        current = new LabBroker(broker.brokerId(), broker.image(), broker.containerId(), broker.host(), broker.port(),
                broker.user(), identity.version(), identity.nodeId(), broker.startedAt());
        return current;
    }

    private void closeFactories()
    {
        try
        {
            if (holdingFactory != null)
            {
                holdingFactory.close();
            }
        }
        finally
        {
            holdingFactory = null;
            if (factory != null)
            {
                factory.close();
            }
            factory = null;
        }
    }

    public List<BrokerLauncher.Leftover> leftovers()
    {
        return launcher.leftovers();
    }

    public void removeLeftover(String containerId)
    {
        launcher.removeLeftover(containerId);
    }

    @PreDestroy
    void shutdown()
    {
        if (current != null)
        {
            stop();
        }
    }

    /**
     * Topology load balancing off, always: without it a 2.57.0 broker sends a factory's second connection wherever its
     * announced topology says. {@code callTimeout} bounds every blocking send.
     */
    String url(String host, int port)
    {
        return "tcp://" + host + ":" + port + "?useTopologyForLoadBalancing=false&callTimeout="
                + limits.operationTimeout().toMillis();
    }

    private Identity identify(ActiveMQConnectionFactory startedFactory) throws JMSException
    {
        Connection first = startedFactory.createConnection(properties.user(), properties.password());
        Connection second = startedFactory.createConnection(properties.user(), properties.password());
        try
        {
            first.start();
            second.start();
            try (ManagementClient one = new ManagementClient(first, limits.operationTimeout());
                    ManagementClient two = new ManagementClient(second, limits.operationTimeout()))
            {
                String nodeOne = one.nodeId();
                String nodeTwo = two.nodeId();
                if (!nodeOne.equals(nodeTwo))
                {
                    throw new LabException("Two connections to the new broker reached different brokers (" + nodeOne
                            + ", " + nodeTwo + "); stopped it without using it.");
                }
                return new Identity(nodeOne, one.version());
            }
        }
        finally
        {
            second.close();
            first.close();
        }
    }

    private record Identity(String nodeId, String version)
    {
    }
}
