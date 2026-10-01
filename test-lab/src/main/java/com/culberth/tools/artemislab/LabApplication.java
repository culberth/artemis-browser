package com.culberth.tools.artemislab;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * The Artemis regression lab: prepares repeatable conditions on disposable test brokers it provisions itself, so every
 * Artemis Browser feature can be demonstrated and regression tested.
 *
 * <p>
 * Unlike Artemis Browser this application writes — it creates queues, sends messages and removes what it made — which
 * is why it is a separate executable with its own port and never shares a jar, a classpath or a broker client with the
 * Browser. It only ever writes to a broker it started, whose identity it has verified on the connection doing the
 * writing.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class LabApplication
{

    public static void main(String[] args)
    {
        SpringApplication.run(LabApplication.class, args);
    }
}
