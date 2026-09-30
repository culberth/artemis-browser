package com.culberth.tools.artemisbrowser.web;

import com.culberth.tools.artemisbrowser.broker.BrokerException;
import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.DeadLetterTriage;
import com.culberth.tools.artemisbrowser.broker.DeadLetterTriageService;
import com.culberth.tools.artemisbrowser.broker.InvalidFilterException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Dead-letter and expiry triage: one queue's sample grouped by origin, on request.
 *
 * <p>
 * Only a queue the broker itself lists is read, as on the queue page, and only when the page is opened — nothing here
 * runs in the background, retries, moves or deletes a message.
 */
@Controller
public class TriageController
{

    private final BrokerSession brokerSession;
    private final DeadLetterTriageService triageService;

    public TriageController(BrokerSession brokerSession, DeadLetterTriageService triageService)
    {
        this.brokerSession = brokerSession;
        this.triageService = triageService;
    }

    @GetMapping("/triage")
    public String triage(@RequestParam(name = "name") String name,
            @RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "sample", defaultValue = "0") int sample,
            @RequestParam(name = "by", required = false) String by, Model model)
    {
        if (!brokerSession.isConnected())
        {
            return "redirect:/";
        }
        model.addAttribute("connection", brokerSession.info());
        model.addAttribute("name", name);
        model.addAttribute("filter", filter == null ? "" : filter);
        model.addAttribute("by", by == null ? "" : by.trim());
        model.addAttribute("sample",
                sample <= 0 ? triageService.defaultSample() : Math.min(sample, triageService.maxSample()));
        model.addAttribute("maxSample", triageService.maxSample());
        try
        {
            DeadLetterTriage triage = triageService.triage(name, filter, sample, by);
            if (triage == null)
            {
                model.addAttribute("error", "No queue named '" + name + "' on this broker.");
            }
            model.addAttribute("triage", triage);
        }
        catch (InvalidFilterException e)
        {
            model.addAttribute("error", e.getMessage());
            model.addAttribute("invalidFilter", true);
        }
        catch (BrokerException e)
        {
            model.addAttribute("error", e.getMessage());
        }
        return "triage";
    }
}
