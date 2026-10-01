package com.culberth.tools.artemisbrowser.web;

import com.culberth.tools.artemisbrowser.broker.AddressDetail;
import com.culberth.tools.artemisbrowser.broker.AddressDetailService;
import com.culberth.tools.artemisbrowser.broker.AddressDirectory;
import com.culberth.tools.artemisbrowser.broker.BrokerException;
import com.culberth.tools.artemisbrowser.broker.BrokerInfoService;
import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.ClientDirectory;
import com.culberth.tools.artemisbrowser.broker.ClientView;
import com.culberth.tools.artemisbrowser.broker.ConnectivityService;
import com.culberth.tools.artemisbrowser.broker.PermissionService;
import com.culberth.tools.artemisbrowser.broker.QueueDirectory;
import com.culberth.tools.artemisbrowser.broker.Reading;
import com.culberth.tools.artemisbrowser.broker.TransactionService;
import com.culberth.tools.artemisbrowser.broker.Transactions;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** The broker's own health, the addresses on it, its paths to other brokers, and its transactions. */
@Controller
public class BrokerController
{

    private static final List<Integer> REFRESH_CHOICES = List.of(0, 5, 15, 30, 60);

    /** Most addresses one client page reads roles for — one call each. */
    static final int CLIENT_PERMISSION_LIMIT = 50;

    private final BrokerSession brokerSession;
    private final BrokerInfoService brokerInfo;
    private final AddressDirectory addressDirectory;
    private final AddressDetailService addressDetail;
    private final ClientDirectory clientDirectory;
    private final ConnectivityService connectivity;
    private final QueueDirectory queueDirectory;
    private final TransactionService transactions;
    private final PermissionService permissions;

    public BrokerController(BrokerSession brokerSession, BrokerInfoService brokerInfo,
            AddressDirectory addressDirectory, AddressDetailService addressDetail, ClientDirectory clientDirectory,
            ConnectivityService connectivity, QueueDirectory queueDirectory, TransactionService transactions,
            PermissionService permissions)
    {
        this.transactions = transactions;
        this.permissions = permissions;
        this.brokerSession = brokerSession;
        this.brokerInfo = brokerInfo;
        this.addressDirectory = addressDirectory;
        this.addressDetail = addressDetail;
        this.clientDirectory = clientDirectory;
        this.connectivity = connectivity;
        this.queueDirectory = queueDirectory;
    }

    /**
     * One client: by its client id, or — for the many that set none — by one of its connections. What it is connected
     * with, what it consumes and holds in flight, and where it sends.
     */
    @GetMapping("/client")
    public String client(@RequestParam(name = "id", required = false) String id,
            @RequestParam(name = "connection", required = false) String connection, Model model)
    {
        if (!brokerSession.isConnected())
        {
            return "redirect:/";
        }
        if ((id == null || id.isBlank()) && (connection == null || connection.isBlank()))
        {
            return "redirect:/broker";
        }
        model.addAttribute("connection", brokerSession.info());
        try
        {
            ClientView client = clientDirectory.find(id, connection);
            if (client == null)
            {
                model.addAttribute("error", (id == null || id.isBlank() ? "No connection '" + connection + "'"
                        : "No client with id '" + id + "'")
                        + " is connected to this broker now. It may have disconnected since the link was drawn.");
            }
            model.addAttribute("client", client);
            if (client != null)
            {
                // What it uses, consumed from and sent to, and who may do that there. The user it
                // connects as is on the page; which roles that user holds is not reported.
                Set<String> used = new LinkedHashSet<>();
                client.consumers().stream().map(ClientView.Consumer::address).filter(a -> a != null && !a.isEmpty())
                        .forEach(used::add);
                client.producers().stream().map(ClientView.Producer::address).filter(a -> a != null && !a.isEmpty())
                        .forEach(used::add);
                model.addAttribute("used", List.copyOf(used));
                model.addAttribute("permissions", permissions.forAddresses(used, CLIENT_PERMISSION_LIMIT));
            }
        }
        catch (BrokerException e)
        {
            model.addAttribute("error", e.getMessage());
        }
        return "client";
    }

    @GetMapping("/broker")
    public String broker(@RequestParam(name = "refresh", defaultValue = "0") int refresh, Model model)
    {
        if (!brokerSession.isConnected())
        {
            return "redirect:/";
        }
        model.addAttribute("connection", brokerSession.info());
        model.addAttribute("refresh", REFRESH_CHOICES.contains(refresh) ? refresh : 0);
        model.addAttribute("refreshChoices", REFRESH_CHOICES);
        // The refresh control keeps whatever else describes the view; this page has nothing else
        // to keep. Supplied from here rather than written as an empty literal in the template,
        // because Thymeleaf's fragment-expression parser cannot read SpEL's `{:}`.
        model.addAttribute("viewParams", Map.of());
        // Each panel on its own: a broker that will not list its acceptors to this user — or a
        // version without one of these operations — still shows its connections. Health isolates
        // per attribute inside; only a lost connection ends the page, via the advice.
        model.addAttribute("health", brokerInfo.health());
        model.addAttribute("acceptors", Reading.attempt(brokerInfo::acceptors));
        model.addAttribute("connections", Reading.attempt(brokerInfo::connections));
        model.addAttribute("consumers", Reading.attempt(brokerInfo::consumers));
        model.addAttribute("producers", Reading.attempt(brokerInfo::producers));
        return "broker";
    }

    /**
     * Where messages can leave this broker, and its HA relationships, as it reports them. The queue listing is read
     * once, for the backlogs behind each path; a broker that will not list its queues still shows the paths.
     */
    @GetMapping("/connectivity")
    public String connectivity(@RequestParam(name = "refresh", defaultValue = "0") int refresh, Model model)
    {
        if (!brokerSession.isConnected())
        {
            return "redirect:/";
        }
        model.addAttribute("connection", brokerSession.info());
        model.addAttribute("refresh", REFRESH_CHOICES.contains(refresh) ? refresh : 0);
        model.addAttribute("refreshChoices", REFRESH_CHOICES);
        model.addAttribute("viewParams", Map.of());
        model.addAttribute("net", connectivity.collect(Reading.attempt(queueDirectory::overview)));
        return "connectivity";
    }

    /**
     * Prepared XA transactions and those resolved by hand, as the broker reports them. Read-only: nothing here commits,
     * rolls back or forgets a transaction, and the operations that would are not on the management allowlist.
     */
    @GetMapping("/transactions")
    public String transactions(@RequestParam(name = "refresh", defaultValue = "0") int refresh, Model model)
    {
        if (!brokerSession.isConnected())
        {
            return "redirect:/";
        }
        model.addAttribute("connection", brokerSession.info());
        model.addAttribute("refresh", REFRESH_CHOICES.contains(refresh) ? refresh : 0);
        model.addAttribute("refreshChoices", REFRESH_CHOICES);
        model.addAttribute("viewParams", Map.of());
        model.addAttribute("tx", transactions.collect());
        model.addAttribute("now", Instant.now());
        return "transactions";
    }

    /** Prepared transactions, or why not; never an empty answer standing for one that was not given. */
    private Reading<Transactions> prepared()
    {
        Reading<Transactions> read = Reading.attempt(transactions::collect);
        return read.available() && read.value() == null ? Reading.failed("the broker gave no answer") : read;
    }

    @GetMapping("/addresses")
    public String addresses(Model model)
    {
        if (!brokerSession.isConnected())
        {
            return "redirect:/";
        }
        model.addAttribute("connection", brokerSession.info());
        try
        {
            model.addAttribute("addresses", addressDirectory.overview());
            model.addAttribute("listed", true);
        }
        catch (BrokerException e)
        {
            // Not listed is not none: no count and no "reported no addresses" for a refused listing.
            model.addAttribute("addresses", List.of());
            model.addAttribute("listed", false);
            model.addAttribute("error", e.getMessage());
        }
        return "addresses";
    }

    /** One address: who is subscribed, how, what each subscription lets through, and who is sending. */
    @GetMapping("/address")
    public String address(@RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "find", required = false) String find, Model model)
    {
        if (!brokerSession.isConnected())
        {
            return "redirect:/";
        }
        if (name == null || name.isBlank())
        {
            return "redirect:/addresses";
        }
        model.addAttribute("connection", brokerSession.info());
        model.addAttribute("name", name);
        model.addAttribute("find", find);
        AddressDetail detail;
        try
        {
            detail = addressDetail.detail(name);
            if (detail == null)
            {
                model.addAttribute("error", "The broker has no address named '" + name + "'. It may have been"
                        + " auto-deleted when its last queue went.");
            }
            model.addAttribute("detail", detail);
            if (detail != null)
            {
                // Each its own panel: a user who may not read roles still sees the subscriptions.
                model.addAttribute("permissions", permissions.forAddress(name));
                model.addAttribute("prepared", prepared());
            }
        }
        catch (BrokerException e)
        {
            model.addAttribute("error", e.getMessage());
            return "address";
        }
        if (detail != null && find != null && !find.isBlank())
        {
            // Its own error slot: a filter the broker rejects should not take the rest of the page
            // down with it.
            try
            {
                model.addAttribute("found", addressDetail.find(detail, find));
            }
            catch (BrokerException e)
            {
                model.addAttribute("findError", e.getMessage());
            }
        }
        return "address";
    }
}
