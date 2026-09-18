package com.culberth.tools.artemisbrowser.web;

import com.culberth.tools.artemisbrowser.broker.BrokerCredentials;
import com.culberth.tools.artemisbrowser.broker.BrokerException;
import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;

@Controller
public class ConnectionController
{

    private final BrokerSession brokerSession;

    public ConnectionController(BrokerSession brokerSession)
    {
        this.brokerSession = brokerSession;
    }

    @GetMapping("/")
    public String index(Model model)
    {
        if (brokerSession.isConnected())
        {
            return "redirect:/queues";
        }
        model.addAttribute("form", new ConnectForm());
        return "connect";
    }

    @PostMapping("/connect")
    public String connect(@ModelAttribute("form") ConnectForm form, Model model)
    {
        String validationError = validate(form);
        if (validationError != null)
        {
            model.addAttribute("error", validationError);
            return "connect";
        }
        try
        {
            brokerSession.connect(new BrokerCredentials(form.getHost().trim(), Integer.parseInt(form.getPort().trim()),
                    form.getUsername().trim(), form.getPassword()));
        }
        catch (BrokerException e)
        {
            model.addAttribute("error", e.getMessage());
            return "connect";
        }
        finally
        {
            // The password has either been used to open the connection or the attempt failed;
            // either way it must not survive into the rendered form or the model.
            form.setPassword("");
        }
        return "redirect:/queues";
    }

    @PostMapping("/disconnect")
    public String disconnect()
    {
        brokerSession.close();
        return "redirect:/";
    }

    private String validate(ConnectForm form)
    {
        if (isBlank(form.getHost()))
        {
            return "Host is required.";
        }
        if (isBlank(form.getPort()))
        {
            return "Port is required.";
        }
        int port;
        try
        {
            port = Integer.parseInt(form.getPort().trim());
        }
        catch (NumberFormatException e)
        {
            return "Port must be a number.";
        }
        if (port < 1 || port > 65535)
        {
            return "Port must be between 1 and 65535.";
        }
        if (isBlank(form.getUsername()))
        {
            return "Username is required.";
        }
        return null;
    }

    private boolean isBlank(String value)
    {
        return value == null || value.isBlank();
    }
}
