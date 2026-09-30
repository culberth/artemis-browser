package com.culberth.tools.artemisbrowser.web;

import com.culberth.tools.artemisbrowser.broker.BrokerException;
import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.MessageComparisonService;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Two messages compared, on request.
 *
 * <p>
 * The left message is {@code name} and the first {@code id}; the right is {@code name2} (or {@code name} again) and
 * {@code id2} (or the second {@code id}). That lets the queue page send two ticked rows as {@code id=…&id=…} with no
 * script, and the message page and this page's own form name both sides in full. Nothing is read until both are named,
 * and nothing is read in the background.
 */
@Controller
public class MessageCompareController
{

    private final BrokerSession brokerSession;
    private final MessageComparisonService comparisonService;

    public MessageCompareController(BrokerSession brokerSession, MessageComparisonService comparisonService)
    {
        this.brokerSession = brokerSession;
        this.comparisonService = comparisonService;
    }

    @GetMapping("/message/compare")
    public String compare(@RequestParam(name = "name", required = false) String name,
            @RequestParam(name = "id", required = false) List<String> ids,
            @RequestParam(name = "name2", required = false) String name2,
            @RequestParam(name = "id2", required = false) String id2, Model model)
    {
        if (!brokerSession.isConnected())
        {
            return "redirect:/";
        }
        model.addAttribute("connection", brokerSession.info());

        List<String> chosen = ids == null ? List.of()
                : ids.stream().filter(id -> !blank(id)).map(String::trim).toList();
        String leftQueue = trim(name);
        String leftId = chosen.isEmpty() ? "" : chosen.get(0);
        String rightQueue = blank(name2) ? leftQueue : name2.trim();
        String rightId = !blank(id2) ? id2.trim() : chosen.size() > 1 ? chosen.get(1) : "";

        model.addAttribute("name", leftQueue);
        model.addAttribute("id", leftId);
        model.addAttribute("name2", rightQueue);
        model.addAttribute("id2", rightId);
        model.addAttribute("maxBodyChars", comparisonService.maxBodyChars());

        if (chosen.size() > 2 || (chosen.size() == 2 && !blank(id2)))
        {
            model.addAttribute("error", "Choose exactly two messages to compare; "
                    + (chosen.size() + (blank(id2) ? 0 : 1)) + " were chosen. The first two are filled in below.");
            return "message-compare";
        }
        if (leftQueue.isEmpty() || leftId.isEmpty() || rightQueue.isEmpty() || rightId.isEmpty())
        {
            if (!chosen.isEmpty() || !blank(id2))
            {
                model.addAttribute("error",
                        "Two messages are needed: name a queue and a message ID for each side. Nothing was read.");
            }
            return "message-compare";
        }
        try
        {
            model.addAttribute("comparison", comparisonService.compare(leftQueue, leftId, rightQueue, rightId));
        }
        catch (BrokerException e)
        {
            model.addAttribute("error", e.getMessage());
        }
        return "message-compare";
    }

    private static boolean blank(String value)
    {
        return value == null || value.isBlank();
    }

    private static String trim(String value)
    {
        return value == null ? "" : value.trim();
    }
}
