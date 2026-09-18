package com.culberth.tools.artemisbrowser.web;

import com.culberth.tools.artemisbrowser.broker.BrokerException;
import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.MessageDetail;
import com.culberth.tools.artemisbrowser.broker.QueueBrowseService;
import com.culberth.tools.artemisbrowser.broker.QueueDirectory;
import com.culberth.tools.artemisbrowser.broker.QueueOverview;
import com.culberth.tools.artemisbrowser.broker.QueueStats;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class QueueController
{

    private static final int MAX_PAGE_SIZE = 500;
    private static final List<Integer> PAGE_SIZE_CHOICES = List.of(10, 25, 50, 100, 250, 500);
    /** Auto-refresh intervals in seconds; 0 is off. */
    private static final List<Integer> REFRESH_CHOICES = List.of(0, 5, 15, 30, 60);

    private final BrokerSession brokerSession;
    private final QueueDirectory queueDirectory;
    private final QueueBrowseService browseService;

    public QueueController(BrokerSession brokerSession, QueueDirectory queueDirectory, QueueBrowseService browseService)
    {
        this.brokerSession = brokerSession;
        this.queueDirectory = queueDirectory;
        this.browseService = browseService;
    }

    /** All queues and their counters at a glance, optionally refreshing on a timer. */
    @GetMapping("/overview")
    public String overview(@RequestParam(name = "refresh", defaultValue = "0") int refresh, Model model)
    {

        if (!brokerSession.isConnected())
        {
            return "redirect:/";
        }
        model.addAttribute("connection", brokerSession.info());
        model.addAttribute("refresh", normaliseRefresh(refresh));
        model.addAttribute("refreshChoices", REFRESH_CHOICES);
        try
        {
            model.addAttribute("queues", queueDirectory.overview());
        }
        catch (BrokerException e)
        {
            model.addAttribute("queues", List.of());
            model.addAttribute("error", e.getMessage());
        }
        return "overview";
    }

    @GetMapping("/queues")
    public String queues(@RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "50") int size,
            @RequestParam(name = "refresh", defaultValue = "0") int refresh, Model model)
    {

        if (!brokerSession.isConnected())
        {
            return "redirect:/";
        }

        int pageSize = Math.clamp(size, 1, MAX_PAGE_SIZE);
        model.addAttribute("connection", brokerSession.info());
        model.addAttribute("size", pageSize);
        model.addAttribute("sizeChoices", PAGE_SIZE_CHOICES);
        model.addAttribute("refresh", normaliseRefresh(refresh));
        model.addAttribute("refreshChoices", REFRESH_CHOICES);
        model.addAttribute("filter", filter == null ? "" : filter);

        List<QueueOverview> queues;
        try
        {
            queues = queueDirectory.overview();
        }
        catch (BrokerException e)
        {
            model.addAttribute("queueNames", List.of());
            model.addAttribute("error", e.getMessage());
            return "queues";
        }
        model.addAttribute("queueNames", queues.stream().map(QueueOverview::name).toList());

        if (name == null || name.isBlank())
        {
            return "queues";
        }
        if (queues.stream().noneMatch(queue -> queue.name().equals(name)))
        {
            // Only ever browse a name the broker itself just listed; never a name off the query
            // string. Without this, ?name= is a free-form handle onto any address on the broker.
            model.addAttribute("error", "No queue named '" + name + "' on this broker.");
            return "queues";
        }

        model.addAttribute("selected", name);
        try
        {
            QueueStats stats = queueDirectory.stats(name);
            model.addAttribute("stats", stats);
            model.addAttribute("messages", browseService.page(name, filter, Math.max(1, page), pageSize));
        }
        catch (BrokerException e)
        {
            model.addAttribute("error", filterHint(e.getMessage(), filter));
        }
        return "queues";
    }

    /** One message in full. */
    @GetMapping("/message")
    public String message(@RequestParam(name = "name") String name, @RequestParam(name = "id") String id, Model model)
    {

        if (!brokerSession.isConnected())
        {
            return "redirect:/";
        }
        model.addAttribute("connection", brokerSession.info());

        QueueStats stats;
        try
        {
            stats = queueDirectory.stats(name);
        }
        catch (BrokerException e)
        {
            model.addAttribute("error", e.getMessage());
            return "message";
        }
        if (stats == null)
        {
            model.addAttribute("error", "No queue named '" + name + "' on this broker.");
            return "message";
        }

        model.addAttribute("queueName", name);
        try
        {
            MessageDetail detail = browseService.detail(name, stats.browseName(), id);
            if (detail == null)
            {
                model.addAttribute("error", "That message is no longer on '" + name + "'. It may have been consumed or"
                        + " expired since the list was drawn.");
            }
            model.addAttribute("message", detail);
        }
        catch (BrokerException e)
        {
            model.addAttribute("error", e.getMessage());
        }
        return "message";
    }

    private int normaliseRefresh(int refresh)
    {
        return REFRESH_CHOICES.contains(refresh) ? refresh : 0;
    }

    /**
     * A filter that is valid syntax but uses JMS names matches nothing rather than failing, so a bare broker error here
     * is usually less helpful than naming the dialect.
     */
    private String filterHint(String message, String filter)
    {
        if (filter == null || filter.isBlank())
        {
            return message;
        }
        return message + " — note that filters use Artemis core syntax (AMQPriority, AMQTimestamp,"
                + " AMQDurable, or a property name), not JMS selector names.";
    }
}
