package com.culberth.tools.artemisbrowser.web;

import com.culberth.tools.artemisbrowser.broker.AddressDirectory;
import com.culberth.tools.artemisbrowser.broker.BrokerException;
import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.ConnectionInfo;
import com.culberth.tools.artemisbrowser.broker.QueueDirectory;
import com.culberth.tools.artemisbrowser.filter.SavedSearch;
import com.culberth.tools.artemisbrowser.filter.SavedSearchException;
import com.culberth.tools.artemisbrowser.filter.SavedSearchStore;
import java.util.Locale;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * Saved searches: list, save, rename, delete, and open one to run.
 *
 * <p>
 * Nothing here runs a search. Opening a saved search shows the broker this session is connected to, the scope, and
 * whether that queue or address still exists — one listing read — and the search runs only when the Run button is
 * pressed, on the page it was saved from. So a saved search against a queue that has gone is explained before it is
 * run, rather than returning what looks like zero matches; and there is no background or scheduled running at all.
 *
 * <p>
 * The list, rename and delete work without a broker connection: the saved values are this machine's, and seeing or
 * removing them should not depend on reaching a broker.
 */
@Controller
public class SavedSearchController
{

    private final SavedSearchStore store;
    private final BrokerSession brokerSession;
    private final QueueDirectory queueDirectory;
    private final AddressDirectory addressDirectory;

    public SavedSearchController(SavedSearchStore store, BrokerSession brokerSession, QueueDirectory queueDirectory,
            AddressDirectory addressDirectory)
    {
        this.store = store;
        this.brokerSession = brokerSession;
        this.queueDirectory = queueDirectory;
        this.addressDirectory = addressDirectory;
    }

    /** Whether the saved scope is there now, and what the Run form submits to. */
    public record ScopeCheck(boolean exists, boolean checked, String explanation)
    {
    }

    @GetMapping("/saved")
    public String list(Model model)
    {
        connection(model);
        model.addAttribute("listing", store.all());
        model.addAttribute("file", store.file().toAbsolutePath().toString());
        model.addAttribute("maxEntries", store.maxEntries());
        return "saved";
    }

    @PostMapping("/saved")
    public String save(@RequestParam(name = "name", defaultValue = "") String name,
            @RequestParam(name = "filter", defaultValue = "") String filter,
            @RequestParam(name = "scope", defaultValue = "ALL_QUEUES") String scope,
            @RequestParam(name = "target", defaultValue = "") String target,
            @RequestParam(name = "internal", defaultValue = "false") boolean internal, RedirectAttributes redirect)
    {
        SavedSearch.Scope parsed;
        try
        {
            parsed = SavedSearch.Scope.valueOf(scope.trim().toUpperCase(Locale.ROOT));
        }
        catch (IllegalArgumentException e)
        {
            redirect.addFlashAttribute("problem", "Unknown scope '" + scope + "'; nothing was saved.");
            return "redirect:/saved";
        }
        try
        {
            SavedSearch saved = store.create(name, filter, parsed, target, internal, brokerAddress());
            redirect.addFlashAttribute("notice", "Saved '" + saved.name() + "'.");
        }
        catch (SavedSearchException e)
        {
            redirect.addFlashAttribute("problem", "Not saved: " + e.getMessage());
        }
        return "redirect:/saved";
    }

    @PostMapping("/saved/{id}/rename")
    public String rename(@PathVariable("id") String id, @RequestParam(name = "name", defaultValue = "") String name,
            RedirectAttributes redirect)
    {
        try
        {
            SavedSearch renamed = store.rename(id, name);
            redirect.addFlashAttribute("notice", "Renamed to '" + renamed.name() + "'.");
        }
        catch (SavedSearchException e)
        {
            redirect.addFlashAttribute("problem", "Not renamed: " + e.getMessage());
        }
        return "redirect:/saved";
    }

    @PostMapping("/saved/{id}/delete")
    public String delete(@PathVariable("id") String id, RedirectAttributes redirect)
    {
        try
        {
            SavedSearch existing = store.find(id);
            boolean deleted = store.delete(id);
            redirect.addFlashAttribute(deleted ? "notice" : "problem",
                    deleted ? "Deleted '" + existing.name() + "'." : "That saved search was already gone.");
        }
        catch (SavedSearchException e)
        {
            redirect.addFlashAttribute("problem", "Not deleted: " + e.getMessage());
        }
        return "redirect:/saved";
    }

    /** One saved search, with the broker and scope it would run against — and a button, not a run. */
    @GetMapping("/saved/{id}")
    public String open(@PathVariable("id") String id, Model model)
    {
        ConnectionInfo info = connection(model);
        SavedSearch search = store.find(id);
        if (search == null)
        {
            model.addAttribute("problem", "There is no saved search with that id. It may have been deleted.");
            return "saved-search";
        }
        model.addAttribute("search", search);
        if (info == null)
        {
            return "saved-search";
        }
        String current = info.host() + ":" + info.port();
        model.addAttribute("currentBroker", current);
        model.addAttribute("otherBroker", !search.savedFrom().isBlank() && !search.savedFrom().equals(current));
        model.addAttribute("scopeCheck", check(search));
        return "saved-search";
    }

    private ScopeCheck check(SavedSearch search)
    {
        try
        {
            return switch (search.scope())
            {
                case ALL_QUEUES -> new ScopeCheck(true, true, "Every queue this broker lists when it runs.");
                case QUEUE -> queueDirectory.stats(search.target()) != null
                        ? new ScopeCheck(true, true, "Queue " + search.target() + " is on this broker.")
                        : new ScopeCheck(false, true, "This broker has no queue named " + search.target()
                                + ". It may have been deleted, auto-deleted when its last consumer or message went,"
                                + " or never existed here. Running it would find nothing, and that would not mean"
                                + " the messages are gone.");
                case ADDRESS -> addressDirectory.find(search.target()) != null
                        ? new ScopeCheck(true, true, "Address " + search.target() + " is on this broker.")
                        : new ScopeCheck(false, true, "This broker has no address named " + search.target()
                                + ". It may have been auto-deleted when its last queue went, or never existed here.");
            };
        }
        catch (BrokerException e)
        {
            return new ScopeCheck(false, false, "Could not check whether it exists: " + e.getMessage());
        }
    }

    private ConnectionInfo connection(Model model)
    {
        if (!brokerSession.isConnected())
        {
            return null;
        }
        ConnectionInfo info = brokerSession.info();
        model.addAttribute("connection", info);
        return info;
    }

    /** {@code host:port}, never the user: a saved search records where it was made, not who made it. */
    private String brokerAddress()
    {
        if (!brokerSession.isConnected())
        {
            return "";
        }
        ConnectionInfo info = brokerSession.info();
        return info == null ? "" : info.host() + ":" + info.port();
    }
}
