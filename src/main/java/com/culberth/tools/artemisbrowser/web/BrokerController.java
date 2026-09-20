package com.culberth.tools.artemisbrowser.web;

import com.culberth.tools.artemisbrowser.broker.AddressDirectory;
import com.culberth.tools.artemisbrowser.broker.BrokerException;
import com.culberth.tools.artemisbrowser.broker.BrokerInfoService;
import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** The broker's own health, and the addresses on it. */
@Controller
public class BrokerController
{

    private static final List<Integer> REFRESH_CHOICES = List.of(0, 5, 15, 30, 60);

    private final BrokerSession brokerSession;
    private final BrokerInfoService brokerInfo;
    private final AddressDirectory addressDirectory;

    public BrokerController(BrokerSession brokerSession, BrokerInfoService brokerInfo,
            AddressDirectory addressDirectory)
    {
        this.brokerSession = brokerSession;
        this.brokerInfo = brokerInfo;
        this.addressDirectory = addressDirectory;
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
        try
        {
            model.addAttribute("health", brokerInfo.health());
            model.addAttribute("acceptors", brokerInfo.acceptors());
            model.addAttribute("connections", brokerInfo.connections());
            model.addAttribute("consumers", brokerInfo.consumers());
            model.addAttribute("producers", brokerInfo.producers());
        }
        catch (BrokerException e)
        {
            model.addAttribute("error", e.getMessage());
        }
        return "broker";
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
        }
        catch (BrokerException e)
        {
            model.addAttribute("addresses", List.of());
            model.addAttribute("error", e.getMessage());
        }
        return "addresses";
    }
}
