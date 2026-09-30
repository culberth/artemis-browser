package com.culberth.tools.artemisbrowser.web;

import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.compare.SnapshotComparer;
import com.culberth.tools.artemisbrowser.compare.SnapshotFile;
import com.culberth.tools.artemisbrowser.compare.SnapshotReader;
import com.culberth.tools.artemisbrowser.compare.SnapshotRejected;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * Compares two saved incident snapshots of one broker.
 *
 * <p>
 * Works offline: it reads the two uploaded files and nothing else, so it needs no broker connection and makes no
 * management call even when there is one — the connection only decides whether the page shows the usual nav. The files
 * are read in the request and dropped with it; nothing is stored.
 */
@Controller
public class CompareController
{

    private final BrokerSession brokerSession;
    private final SnapshotReader reader;

    public CompareController(BrokerSession brokerSession, SnapshotReader reader)
    {
        this.brokerSession = brokerSession;
        this.reader = reader;
    }

    @GetMapping("/compare")
    public String form(Model model)
    {
        common(model);
        return "compare";
    }

    @PostMapping("/compare")
    public String compare(@RequestParam(name = "earlier", required = false) MultipartFile earlier,
            @RequestParam(name = "later", required = false) MultipartFile later, Model model)
    {
        common(model);
        if (earlier == null || earlier.isEmpty() || later == null || later.isEmpty())
        {
            model.addAttribute("error", "Choose two snapshot files to compare.");
            return "compare";
        }
        try
        {
            SnapshotFile first = read(earlier, "the first file");
            SnapshotFile second = read(later, "the second file");
            model.addAttribute("result", SnapshotComparer.compare(first, second));
        }
        catch (SnapshotRejected e)
        {
            model.addAttribute("error", e.getMessage());
        }
        return "compare";
    }

    private SnapshotFile read(MultipartFile file, String fallback)
    {
        String label = label(file.getOriginalFilename(), fallback);
        try (InputStream in = file.getInputStream())
        {
            return reader.read(label, in, file.getSize());
        }
        catch (IOException e)
        {
            throw new SnapshotRejected(label + " could not be read: " + e.getMessage());
        }
    }

    /** The uploaded name, shortened and stripped of any path — it is shown back, and it came from the browser. */
    static String label(String original, String fallback)
    {
        if (original == null || original.isBlank())
        {
            return fallback;
        }
        String name = original.substring(Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\')) + 1)
                .replaceAll("\\p{Cntrl}", "");
        name = name.length() > 120 ? name.substring(0, 120) + "…" : name;
        return name.isBlank() ? fallback : "'" + name + "'";
    }

    private void common(Model model)
    {
        if (brokerSession.isConnected())
        {
            model.addAttribute("connection", brokerSession.info());
        }
        model.addAttribute("maxBytes", reader.maxBytes());
        model.addAttribute("maxRows", reader.maxRows());
    }
}
