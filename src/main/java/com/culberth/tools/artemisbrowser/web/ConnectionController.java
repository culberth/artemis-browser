package com.culberth.tools.artemisbrowser.web;

import com.culberth.tools.artemisbrowser.broker.BrokerCredentials;
import com.culberth.tools.artemisbrowser.broker.BrokerException;
import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.ConnectionStore;
import com.culberth.tools.artemisbrowser.broker.SavedConnection;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
public class ConnectionController
{

    private final BrokerSession brokerSession;
    private final ConnectionStore connectionStore;

    public ConnectionController(BrokerSession brokerSession, ConnectionStore connectionStore)
    {
        this.brokerSession = brokerSession;
        this.connectionStore = connectionStore;
    }

    @GetMapping("/")
    public String index(@RequestParam(name = "use", required = false) String use, Model model)
    {
        if (brokerSession.isConnected())
        {
            return "redirect:/overview";
        }
        ConnectForm form = new ConnectForm();
        if (use != null && !use.isBlank())
        {
            SavedConnection saved = connectionStore.find(use);
            if (saved != null)
            {
                form.setHost(saved.host());
                form.setPort(String.valueOf(saved.port()));
                form.setUsername(saved.username());
                form.setLabel(saved.label());
                form.setRemember(true);
            }
        }
        model.addAttribute("form", form);
        model.addAttribute("saved", connectionStore.all());
        return "connect";
    }

    @PostMapping("/connect")
    public String connect(@ModelAttribute("form") ConnectForm form, Model model)
    {
        String validationError = validate(form);
        if (validationError != null)
        {
            return back(model, form, validationError);
        }
        int port = Integer.parseInt(form.getPort().trim());
        try
        {
            brokerSession.connect(
                    new BrokerCredentials(form.getHost().trim(), port, form.getUsername().trim(), form.getPassword()));
        }
        catch (BrokerException e)
        {
            return back(model, form, e.getMessage());
        }
        finally
        {
            // The password has either opened the connection or the attempt failed; either way it
            // must not survive into the rendered form or the model.
            form.setPassword("");
        }

        if (form.isRemember())
        {
            connectionStore
                    .save(new SavedConnection(form.getLabel(), form.getHost().trim(), port, form.getUsername().trim()));
        }
        return "redirect:/overview";
    }

    @PostMapping("/disconnect")
    public String disconnect()
    {
        brokerSession.close();
        return "redirect:/";
    }

    @PostMapping("/connections/forget")
    public String forget(@RequestParam("key") String key)
    {
        connectionStore.remove(key);
        return "redirect:/";
    }

    private String back(Model model, ConnectForm form, String error)
    {
        model.addAttribute("error", error);
        model.addAttribute("saved", connectionStore.all());
        return "connect";
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
