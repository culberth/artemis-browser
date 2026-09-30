package com.culberth.tools.artemislab.broker;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The disposable broker the lab provisions.
 *
 * @param images         the supported matrix, pinned; the form offers only these and nothing takes a free-text image,
 *                       host or URL
 * @param port           the host port published on 127.0.0.1. Refused, never moved, when something already holds it.
 * @param user           the broker's admin user, for the lab and for connecting Artemis Browser
 * @param password       its password. Never written to a run manifest or a link.
 * @param startupTimeout how long pulling and starting the image may take
 */
@ConfigurationProperties("lab.broker")
public record LabBrokerProperties(@DefaultValue(
{
        "apache/artemis:2.55.0-alpine", "apache/artemis:2.57.0-alpine"
    }) List<String> images, @DefaultValue("62616") int port, @DefaultValue("artemis") String user,
        @DefaultValue("artemis") String password, @DefaultValue("4m") Duration startupTimeout) {

    public LabBrokerProperties
    {
        images = List.copyOf(images);
        if (images.isEmpty() || images.stream()
                .anyMatch(image -> image.isBlank() || image.endsWith(":latest") || !image.contains(":")))
        {
            throw new IllegalArgumentException("lab.broker.images must be pinned tags, never latest: " + images);
        }
        if (port < 1024 || port > 65535)
        {
            throw new IllegalArgumentException("lab.broker.port must be 1024-65535; configured " + port);
        }
    }

    public boolean supports(String image)
    {
        return images.contains(image);
    }
}
