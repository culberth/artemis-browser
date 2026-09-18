package com.culberth.tools.artemisbrowser.web;

import com.culberth.tools.artemisbrowser.broker.BrokerException;
import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.QueueBrowseService;
import com.culberth.tools.artemisbrowser.broker.QueueDirectory;
import com.culberth.tools.artemisbrowser.broker.QueueStats;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class QueueController {

    private static final int MAX_LIMIT = 500;
    private static final List<Integer> LIMIT_CHOICES = List.of(10, 25, 50, 100, 250, 500);

    private final BrokerSession brokerSession;
    private final QueueDirectory queueDirectory;
    private final QueueBrowseService browseService;

    public QueueController(BrokerSession brokerSession, QueueDirectory queueDirectory,
            QueueBrowseService browseService) {
        this.brokerSession = brokerSession;
        this.queueDirectory = queueDirectory;
        this.browseService = browseService;
    }

    @GetMapping("/queues")
    public String queues(
            @RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "limit", defaultValue = "50") int limit,
            Model model) {

        if (!brokerSession.isConnected()) {
            return "redirect:/";
        }

        int effectiveLimit = Math.clamp(limit, 1, MAX_LIMIT);
        model.addAttribute("connection", brokerSession.info());
        model.addAttribute("limit", effectiveLimit);
        model.addAttribute("limitChoices", LIMIT_CHOICES);

        List<String> names;
        try {
            names = queueDirectory.queueNames();
        } catch (BrokerException e) {
            model.addAttribute("queueNames", List.of());
            model.addAttribute("error", e.getMessage());
            return "queues";
        }
        model.addAttribute("queueNames", names);

        if (name == null || name.isBlank()) {
            return "queues";
        }
        if (!names.contains(name)) {
            // Only ever browse a name the broker itself just listed; never a name off the query
            // string. Without this, ?name= is a free-form handle onto any address on the broker.
            model.addAttribute("error", "No queue named '" + name + "' on this broker.");
            return "queues";
        }

        model.addAttribute("selected", name);
        try {
            QueueStats stats = queueDirectory.stats(name);
            model.addAttribute("stats", stats);
            model.addAttribute("browse", browseService.browse(name, stats.browseName(), effectiveLimit));
        } catch (BrokerException e) {
            model.addAttribute("error", e.getMessage());
        }
        return "queues";
    }
}
