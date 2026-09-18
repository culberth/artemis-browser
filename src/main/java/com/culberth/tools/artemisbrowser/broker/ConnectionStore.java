package com.culberth.tools.artemisbrowser.broker;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Remembers broker locations between runs, in a JSON file under the user's home directory.
 *
 * <p>
 * Host, port and username only. Never the password — see {@link SavedConnection}.
 *
 * <p>
 * Failures here are logged and swallowed rather than propagated: not being able to remember a hostname is an
 * inconvenience, and it should never be the reason someone cannot connect to their broker. Every read returns a list; a
 * corrupt or unreadable file reads as an empty one.
 */
@Service
public class ConnectionStore
{

    private static final Logger log = LoggerFactory.getLogger(ConnectionStore.class);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path file;

    public ConnectionStore(@Value("${artemis.connections-file:}") String configuredPath)
    {
        this.file = configuredPath == null || configuredPath.isBlank()
                ? Paths.get(System.getProperty("user.home"), ".artemis-browser", "connections.json")
                : Paths.get(configuredPath);
    }

    public List<SavedConnection> all()
    {
        if (!Files.isRegularFile(file))
        {
            return List.of();
        }
        try
        {
            List<SavedConnection> saved = objectMapper.readValue(Files.readAllBytes(file),
                    new TypeReference<List<SavedConnection>>()
                    {
                    });
            List<SavedConnection> sorted = new ArrayList<>(saved);
            sorted.sort(Comparator.comparing(SavedConnection::displayName, String.CASE_INSENSITIVE_ORDER));
            return sorted;
        }
        catch (IOException | RuntimeException e)
        {
            log.warn("Could not read saved connections from {}: {}", file, e.getMessage());
            return List.of();
        }
    }

    /** Adds or replaces the entry for this broker and user. */
    public void save(SavedConnection connection)
    {
        List<SavedConnection> updated = new ArrayList<>();
        for (SavedConnection existing : all())
        {
            if (!existing.key().equals(connection.key()))
            {
                updated.add(existing);
            }
        }
        updated.add(connection);
        write(updated);
    }

    public void remove(String key)
    {
        List<SavedConnection> updated = new ArrayList<>();
        for (SavedConnection existing : all())
        {
            if (!existing.key().equals(key))
            {
                updated.add(existing);
            }
        }
        write(updated);
    }

    public SavedConnection find(String key)
    {
        for (SavedConnection existing : all())
        {
            if (existing.key().equals(key))
            {
                return existing;
            }
        }
        return null;
    }

    private void write(List<SavedConnection> connections)
    {
        try
        {
            Path parent = file.getParent();
            if (parent != null)
            {
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), connections);
        }
        catch (IOException | RuntimeException e)
        {
            log.warn("Could not save connections to {}: {}", file, e.getMessage());
        }
    }
}
