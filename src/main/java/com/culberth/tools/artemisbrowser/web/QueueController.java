package com.culberth.tools.artemisbrowser.web;

import com.culberth.tools.artemisbrowser.broker.BrokerException;
import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.MessageDetail;
import com.culberth.tools.artemisbrowser.broker.QueueBrowseService;
import com.culberth.tools.artemisbrowser.broker.QueueDirectory;
import com.culberth.tools.artemisbrowser.broker.QueueOverview;
import com.culberth.tools.artemisbrowser.broker.QueueStats;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import tools.jackson.databind.ObjectMapper;

@Controller
public class QueueController
{

    private static final int MAX_PAGE_SIZE = 500;
    /** Sortable overview columns. Anything else in the query string falls back to name. */
    private static final Map<String, Comparator<QueueOverview>> SORTS = Map.of("name",
            Comparator.comparing(queue -> queue.name().toLowerCase()), "address",
            Comparator.comparing(queue -> queue.address().toLowerCase()), "messages",
            Comparator.comparingLong(QueueOverview::messageCount), "delivering",
            Comparator.comparingLong(QueueOverview::deliveringCount), "scheduled",
            Comparator.comparingLong(QueueOverview::scheduledCount), "consumers",
            Comparator.comparingInt(QueueOverview::consumerCount), "added",
            Comparator.comparingLong(QueueOverview::messagesAdded), "acked",
            Comparator.comparingLong(QueueOverview::messagesAcked));
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
    public String overview(@RequestParam(name = "refresh", defaultValue = "0") int refresh,
            @RequestParam(name = "sort", defaultValue = "name") String sort,
            @RequestParam(name = "dir", defaultValue = "asc") String dir,
            @RequestParam(name = "q", required = false) String q, Model model)
    {

        if (!brokerSession.isConnected())
        {
            return "redirect:/";
        }
        String sortKey = SORTS.containsKey(sort) ? sort : "name";
        boolean descending = "desc".equalsIgnoreCase(dir);
        String search = q == null ? "" : q.trim();

        model.addAttribute("connection", brokerSession.info());
        model.addAttribute("refresh", normaliseRefresh(refresh));
        model.addAttribute("refreshChoices", REFRESH_CHOICES);
        model.addAttribute("sort", sortKey);
        model.addAttribute("dir", descending ? "desc" : "asc");
        model.addAttribute("q", search);
        // Auto-refresh re-requests the current URL and keeps these for free; the refresh form has
        // to carry them itself, or changing the interval would silently reset the view.
        model.addAttribute("viewParams", Map.of("sort", sortKey, "dir", descending ? "desc" : "asc", "q", search));

        try
        {
            List<QueueOverview> all = queueDirectory.overview();
            List<QueueOverview> shown = all.stream().filter(queue -> matches(queue, search))
                    .sorted(order(sortKey, descending)).toList();
            model.addAttribute("queues", shown);
            model.addAttribute("totalQueues", all.size());
        }
        catch (BrokerException e)
        {
            model.addAttribute("queues", List.of());
            model.addAttribute("totalQueues", 0);
            model.addAttribute("error", e.getMessage());
        }
        return "overview";
    }

    /**
     * Narrowing is on name and address together, because "which queues belong to this address" and "which queue was
     * that" are the same question asked two ways, and a broker with 200 queues makes both unanswerable by eye.
     */
    private boolean matches(QueueOverview queue, String search)
    {
        if (search.isEmpty())
        {
            return true;
        }
        String needle = search.toLowerCase();
        return queue.name().toLowerCase().contains(needle) || queue.address().toLowerCase().contains(needle);
    }

    /** Name breaks every tie, so a refresh cannot shuffle rows that sort equal. */
    private Comparator<QueueOverview> order(String sortKey, boolean descending)
    {
        Comparator<QueueOverview> comparator = SORTS.get(sortKey);
        return (descending ? comparator.reversed() : comparator).thenComparing(queue -> queue.name().toLowerCase());
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
            // Scheduled messages are counted by the queue but not returned by browse, so without
            // this the page can report messages and show an empty table.
            if (stats != null && stats.scheduledCount() > 0)
            {
                model.addAttribute("scheduled", browseService.scheduled(name));
            }
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

    /**
     * One message as a file: headers, properties and the whole body together.
     *
     * <p>
     * The detail page already shows all three, but getting them out of it means three selections and a lost format.
     * Attaching a message to a ticket is most of what someone does after finding it.
     */
    @GetMapping("/message/download")
    public void download(@RequestParam(name = "name") String name, @RequestParam(name = "id") String id,
            @RequestParam(name = "format", defaultValue = "txt") String format, HttpServletResponse response)
            throws IOException
    {

        if (!brokerSession.isConnected())
        {
            response.sendRedirect("/");
            return;
        }
        QueueStats stats = queueDirectory.stats(name);
        if (stats == null)
        {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "No such queue on this broker.");
            return;
        }
        MessageDetail detail = browseService.detail(name, stats.browseName(), id);
        if (detail == null)
        {
            response.sendError(HttpServletResponse.SC_NOT_FOUND,
                    "That message is no longer on this queue. It may have been consumed or expired.");
            return;
        }

        boolean json = "json".equalsIgnoreCase(format);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(json ? "application/json" : "text/plain");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + fileName(name, detail.messageId(), json ? "json" : "txt") + "\"");

        if (json)
        {
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(response.getWriter(), detail);
        }
        else
        {
            writeText(response.getWriter(), detail);
        }
        response.getWriter().flush();
    }

    private void writeText(PrintWriter writer, MessageDetail detail)
    {
        writer.println("Queue:         " + detail.queueName());
        writer.println("Message ID:    " + detail.messageId());
        writer.println("Correlation:   " + (detail.correlationId() == null ? "-" : detail.correlationId()));
        writer.println("Type:          " + detail.type());
        writer.println("Destination:   " + detail.destination());
        writer.println("Timestamp:     " + detail.timestampText());
        writer.println("Expires:       " + detail.expirationText());
        writer.println("Priority:      " + detail.priority());
        writer.println("Persistent:    " + detail.persistent());
        writer.println("Redelivered:   " + detail.redelivered());
        writer.println("Delivery count:" + detail.deliveryCount());
        writer.println("Group ID:      " + (detail.groupId() == null ? "-" : detail.groupId()));
        writer.println("Large message: " + detail.largeMessage());
        writer.println();
        writer.println("Properties (" + detail.properties().size() + ")");
        detail.properties().forEach((key, value) -> writer.println("  " + key + " = " + value));
        writer.println();
        writer.println("Body" + (detail.bodyTruncated() ? " (truncated)" : ""));
        writer.println(detail.body());
    }

    /**
     * Both halves of the name come from the broker rather than from us, so both are reduced to a safe set: a queue or
     * message id containing a quote or a path separator would otherwise escape the quoted header value.
     */
    private String fileName(String queueName, String messageId, String extension)
    {
        String safeQueue = queueName.replaceAll("[^A-Za-z0-9._-]", "_");
        String safeId = messageId == null ? "message" : messageId.replaceAll("[^A-Za-z0-9._-]", "_");
        return (safeQueue.isBlank() ? "queue" : safeQueue) + "-" + safeId + "." + extension;
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
