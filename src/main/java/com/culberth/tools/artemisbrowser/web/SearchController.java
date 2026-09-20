package com.culberth.tools.artemisbrowser.web;

import com.culberth.tools.artemisbrowser.broker.BrokerException;
import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.MessageExporter;
import com.culberth.tools.artemisbrowser.broker.MessagePage;
import com.culberth.tools.artemisbrowser.broker.MessageSearchService;
import com.culberth.tools.artemisbrowser.broker.MessageSummary;
import com.culberth.tools.artemisbrowser.broker.QueueBrowseService;
import com.culberth.tools.artemisbrowser.broker.QueueDirectory;
import com.culberth.tools.artemisbrowser.broker.QueueStats;
import com.culberth.tools.artemisbrowser.broker.SearchResult;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class SearchController
{

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BrokerSession brokerSession;
    private final MessageSearchService searchService;
    private final QueueDirectory queueDirectory;
    private final QueueBrowseService browseService;
    private final MessageExporter exporter;
    private final int exportMax;

    public SearchController(BrokerSession brokerSession, MessageSearchService searchService,
            QueueDirectory queueDirectory, QueueBrowseService browseService, MessageExporter exporter,
            @Value("${artemis.export-max-messages:5000}") int exportMax)
    {
        this.brokerSession = brokerSession;
        this.searchService = searchService;
        this.queueDirectory = queueDirectory;
        this.browseService = browseService;
        this.exporter = exporter;
        this.exportMax = exportMax;
    }

    @GetMapping("/search")
    public String search(@RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "internal", defaultValue = "false") boolean internal, Model model)
    {

        if (!brokerSession.isConnected())
        {
            return "redirect:/";
        }
        model.addAttribute("connection", brokerSession.info());
        model.addAttribute("filter", filter == null ? "" : filter);
        model.addAttribute("internal", internal);

        if (filter == null || filter.isBlank())
        {
            return "search";
        }
        try
        {
            model.addAttribute("result", searchService.search(filter, internal));
        }
        catch (BrokerException e)
        {
            model.addAttribute("error",
                    e.getMessage() + " — filters use Artemis core syntax (AMQPriority, AMQTimestamp, AMQDurable,"
                            + " or a property name), not JMS selector names.");
        }
        return "search";
    }

    /**
     * Streams messages as CSV or JSON: one queue's when {@code name} is given, otherwise everything a cross-queue
     * search matched.
     */
    @GetMapping("/export")
    public void export(@RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "format", defaultValue = "csv") String format,
            @RequestParam(name = "internal", defaultValue = "false") boolean internal, HttpServletResponse response)
            throws IOException
    {

        if (!brokerSession.isConnected())
        {
            response.sendRedirect("/");
            return;
        }
        if (name == null || name.isBlank())
        {
            exportSearch(filter, format, internal, response);
            return;
        }
        // Same rule as browsing: only a name the broker itself just listed. The stats also carry the
        // FQQN the bodies have to be browsed under, which is not the queue name for a multicast
        // subscription.
        QueueStats stats = queueDirectory.stats(name);
        if (stats == null)
        {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "No such queue on this broker.");
            return;
        }

        boolean json = "json".equalsIgnoreCase(format);
        MessagePage page = browseService.pageForExport(name, stats.browseName(), filter, 1, exportMax);

        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(json ? "application/json" : "text/csv");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + fileName(name, json ? "json" : "csv") + "\"");

        PrintWriter writer = response.getWriter();
        if (json)
        {
            exporter.writeJson(writer, name, page.filter(), page.messages());
        }
        else
        {
            exporter.writeCsv(writer, name, page.messages());
        }
    }

    /**
     * Every message a search matched, across every queue it matched in.
     *
     * <p>
     * Deliberately not the search page's own result: that one stops at {@code artemis.search-max-per-queue} and carries
     * table-sized body previews. This re-reads each matching queue with export bodies, and writes each queue out before
     * fetching the next, so a search that matched in thirty queues does not hold thirty queues' worth of bodies at
     * once. The overall message count is capped by {@code artemis.export-max-messages}, as a single-queue export is.
     */
    private void exportSearch(String filter, String format, boolean internal, HttpServletResponse response)
            throws IOException
    {
        if (filter == null || filter.isBlank())
        {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "A filter is needed to export a search.");
            return;
        }

        boolean json = "json".equalsIgnoreCase(format);
        SearchResult matched = searchService.counts(filter, internal);

        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(json ? "application/json" : "text/csv");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + fileName("search", json ? "json" : "csv") + "\"");

        int remaining = exportMax;
        try (MessageExporter.CrossQueueExport export = exporter.openCrossQueueExport(response.getWriter(), json,
                matched.filter()))
        {
            for (SearchResult.QueueMatches match : matched.matches())
            {
                if (remaining <= 0)
                {
                    break;
                }
                QueueStats stats = queueDirectory.stats(match.queueName());
                if (stats == null)
                {
                    // Gone since the count a moment ago; a queue that no longer exists is not an
                    // error for a read-only tool, it is just nothing to export.
                    continue;
                }
                int take = (int) Math.min(remaining, match.matchCount());
                List<MessageSummary> messages = browseService
                        .pageForExport(match.queueName(), stats.browseName(), matched.filter(), 1, take).messages();
                export.write(match.queueName(), messages);
                remaining -= messages.size();
            }
        }
    }

    /**
     * A queue name becomes part of a Content-Disposition filename, and queue names can contain characters ({@code "},
     * {@code ;}, path separators) that would break out of the quoted value or point the download somewhere else.
     * Everything outside a safe set is replaced.
     */
    private String fileName(String queueName, String extension)
    {
        String safe = queueName.replaceAll("[^A-Za-z0-9._-]", "_");
        if (safe.isBlank())
        {
            safe = "queue";
        }
        return safe + "-" + LocalDateTime.now().format(FILE_STAMP) + "." + extension;
    }
}
