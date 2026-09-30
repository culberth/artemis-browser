package com.culberth.tools.artemisbrowser.filter;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Saved searches, in a JSON file beside the remembered connections.
 *
 * <p>
 * Bounded, because it is a file this tool writes on every save: at most {@code max-entries} searches, each name and
 * filter capped, and a file larger than a generous multiple of that is not read at all. It holds names, filters, scopes
 * and the broker's {@code host:port} — see {@link SavedSearch} for what it must never hold.
 *
 * <p>
 * Unlike {@code ConnectionStore}, a failed write is not swallowed: someone who clicks Save and is told nothing believes
 * the search is saved. And a file that cannot be read is never overwritten — saving into it would replace searches this
 * tool could not see with the one it could. Each failure says which file and why, so it can be fixed or removed by
 * hand.
 */
@Service
public class SavedSearchStore
{

    private static final Logger log = LoggerFactory.getLogger(SavedSearchStore.class);

    /**
     * A field added by hand, or by a later version, is ignored, and a missing flag reads as false, rather than one
     * entry making the whole file unreadable. Entries still have to pass {@link #invalid} to be used.
     */
    private final ObjectMapper objectMapper = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .build();
    private final Path file;
    private final int maxEntries;
    private final int maxNameChars;
    private final int maxFilterChars;
    private final Clock clock;

    @Autowired
    public SavedSearchStore(@Value("${artemis.saved-searches.file:}") String configuredPath,
            @Value("${artemis.saved-searches.max-entries:200}") int maxEntries,
            @Value("${artemis.saved-searches.max-name-chars:100}") int maxNameChars,
            @Value("${artemis.saved-searches.max-filter-chars:4000}") int maxFilterChars)
    {
        this(configuredPath, maxEntries, maxNameChars, maxFilterChars, Clock.systemUTC());
    }

    SavedSearchStore(String configuredPath, int maxEntries, int maxNameChars, int maxFilterChars, Clock clock)
    {
        this.file = configuredPath == null || configuredPath.isBlank()
                ? Paths.get(System.getProperty("user.home"), ".artemis-browser", "saved-searches.json")
                : Paths.get(configuredPath);
        this.maxEntries = maxEntries;
        this.maxNameChars = maxNameChars;
        this.maxFilterChars = maxFilterChars;
        this.clock = clock;
    }

    /** What the file holds, as far as it could be used, and what could not. */
    public record Listing(List<SavedSearch> searches, String problem, boolean writable)
    {

        public boolean hasProblem()
        {
            return problem != null;
        }
    }

    public Path file()
    {
        return file;
    }

    public int maxEntries()
    {
        return maxEntries;
    }

    public int maxNameChars()
    {
        return maxNameChars;
    }

    public int maxFilterChars()
    {
        return maxFilterChars;
    }

    public synchronized Listing all()
    {
        if (!Files.exists(file))
        {
            return new Listing(List.of(), null, true);
        }
        try
        {
            long size = Files.size(file);
            if (size > maxFileBytes())
            {
                return new Listing(List.of(),
                        file + " is " + size + " bytes, more than the " + maxFileBytes() + " a file of " + maxEntries
                                + " saved searches can need. It was not read, and will not be" + " overwritten.",
                        false);
            }
            List<SavedSearch> read = objectMapper.readValue(Files.readAllBytes(file),
                    new TypeReference<List<SavedSearch>>()
                    {
                    });
            List<SavedSearch> usable = new ArrayList<>();
            int dropped = 0;
            for (SavedSearch search : read == null ? List.<SavedSearch>of() : read)
            {
                if (search != null && invalid(search) == null && usable.size() < maxEntries)
                {
                    usable.add(search);
                }
                else
                {
                    dropped++;
                }
            }
            usable.sort(Comparator.comparing(SavedSearch::name, String.CASE_INSENSITIVE_ORDER));
            String problem = dropped == 0 ? null
                    : dropped + " entr" + (dropped == 1 ? "y" : "ies") + " in " + file
                            + " could not be used (incomplete, over a limit, or past the first " + maxEntries + ") and "
                            + (dropped == 1 ? "is" : "are") + " not shown. The next save, rename or delete removes "
                            + (dropped == 1 ? "it" : "them") + ".";
            return new Listing(List.copyOf(usable), problem, true);
        }
        catch (IOException | RuntimeException e)
        {
            log.warn("Could not read saved searches from {}: {}", file, e.getMessage());
            return new Listing(List.of(), "Could not read " + file + ": " + e.getMessage()
                    + ". It will not be overwritten; fix or remove it by hand to save searches again.", false);
        }
    }

    public synchronized SavedSearch find(String id)
    {
        return all().searches().stream().filter(search -> search.id().equals(id)).findFirst().orElse(null);
    }

    public synchronized SavedSearch create(String name, String filter, SavedSearch.Scope scope, String target,
            boolean internal, String savedFrom)
    {
        Listing listing = writableListing();
        if (listing.searches().size() >= maxEntries)
        {
            throw new SavedSearchException("There are already " + maxEntries
                    + " saved searches (artemis.saved-searches.max-entries). Delete one first.");
        }
        String cleanName = name == null ? "" : name.trim();
        requireUniqueName(listing, cleanName, null);
        Instant now = clock.instant();
        SavedSearch search = new SavedSearch(UUID.randomUUID().toString(), cleanName,
                filter == null ? "" : filter.trim(), scope,
                scope == SavedSearch.Scope.ALL_QUEUES ? "" : target == null ? "" : target, internal,
                savedFrom == null ? "" : savedFrom, now, now);
        String problem = invalid(search);
        if (problem != null)
        {
            throw new SavedSearchException(problem);
        }
        List<SavedSearch> updated = new ArrayList<>(listing.searches());
        updated.add(search);
        write(updated);
        return search;
    }

    public synchronized SavedSearch rename(String id, String name)
    {
        Listing listing = writableListing();
        SavedSearch existing = listing.searches().stream().filter(search -> search.id().equals(id)).findFirst()
                .orElseThrow(() -> new SavedSearchException("That saved search no longer exists."));
        String cleanName = name == null ? "" : name.trim();
        requireUniqueName(listing, cleanName, id);
        SavedSearch renamed = existing.withName(cleanName, clock.instant());
        String problem = invalid(renamed);
        if (problem != null)
        {
            throw new SavedSearchException(problem);
        }
        write(listing.searches().stream().map(search -> search.id().equals(id) ? renamed : search).toList());
        return renamed;
    }

    /** True when it was there to delete. */
    public synchronized boolean delete(String id)
    {
        Listing listing = writableListing();
        List<SavedSearch> kept = listing.searches().stream().filter(search -> !search.id().equals(id)).toList();
        if (kept.size() == listing.searches().size())
        {
            return false;
        }
        write(kept);
        return true;
    }

    private Listing writableListing()
    {
        Listing listing = all();
        if (!listing.writable())
        {
            throw new SavedSearchException(listing.problem());
        }
        return listing;
    }

    private void requireUniqueName(Listing listing, String name, String exceptId)
    {
        String lower = name.toLowerCase(Locale.ROOT);
        boolean taken = listing.searches().stream().anyMatch(
                search -> !search.id().equals(exceptId) && search.name().toLowerCase(Locale.ROOT).equals(lower));
        if (taken)
        {
            throw new SavedSearchException("There is already a saved search called '" + name + "'.");
        }
    }

    /** Why this entry cannot be kept, or null. Applied to what is read as well as to what is written. */
    private String invalid(SavedSearch search)
    {
        if (search.id() == null || search.id().isBlank())
        {
            return "A saved search needs an id.";
        }
        if (search.name() == null || search.name().isBlank())
        {
            return "A saved search needs a name.";
        }
        if (search.name().length() > maxNameChars)
        {
            return "A name can be at most " + maxNameChars + " characters.";
        }
        if (search.name().chars().anyMatch(Character::isISOControl))
        {
            return "A name cannot contain control characters.";
        }
        if (search.filter() == null || search.filter().isBlank())
        {
            return "There is no filter to save.";
        }
        if (search.filter().length() > maxFilterChars)
        {
            return "A saved filter can be at most " + maxFilterChars + " characters.";
        }
        if (search.scope() == null)
        {
            return "A saved search needs a scope.";
        }
        if (search.scope() != SavedSearch.Scope.ALL_QUEUES && (search.target() == null || search.target().isBlank()))
        {
            return "A saved search on one " + search.scope().label() + " needs its name.";
        }
        if (search.target() != null && search.target().length() > 1000)
        {
            return "That " + search.scope().label() + " name is too long to save.";
        }
        return null;
    }

    /** Room for every entry at its limits, with plenty over for JSON's own text; anything past it is not ours. */
    private long maxFileBytes()
    {
        return (long) maxEntries * (maxNameChars + maxFilterChars + 1500L) * 2 + 4096;
    }

    /** Written beside the file and moved over it, so a failed write leaves the previous file whole. */
    private void write(List<SavedSearch> searches)
    {
        try
        {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path temporary = Files.createTempFile(parent, "saved-searches", ".tmp");
            try
            {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), searches);
                try
                {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                }
                catch (AtomicMoveNotSupportedException e)
                {
                    Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
                }
            }
            finally
            {
                Files.deleteIfExists(temporary);
            }
        }
        catch (IOException | RuntimeException e)
        {
            log.warn("Could not save searches to {}: {}", file, e.getMessage());
            throw new SavedSearchException("Could not write " + file + ": " + e.getMessage(), e);
        }
    }
}
