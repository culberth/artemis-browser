package com.culberth.tools.artemisbrowser.web;

import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.ConnectionInfo;
import com.culberth.tools.artemisbrowser.broker.IncidentSnapshot;
import com.culberth.tools.artemisbrowser.broker.SnapshotService;
import com.culberth.tools.artemisbrowser.broker.SnapshotWriter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The incident snapshot as a download: structured JSON to keep and compare, or a text summary to paste into a ticket.
 * Both are collected fresh on each request; separate from the message exports, and carrying no message body.
 */
@Controller
public class SnapshotController
{

    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final BrokerSession brokerSession;
    private final SnapshotService snapshotService;

    public SnapshotController(BrokerSession brokerSession, SnapshotService snapshotService)
    {
        this.brokerSession = brokerSession;
        this.snapshotService = snapshotService;
    }

    @GetMapping("/snapshot")
    public void snapshot(@RequestParam(name = "format", defaultValue = "json") String format,
            HttpServletResponse response) throws IOException
    {
        if (!brokerSession.isConnected())
        {
            response.sendRedirect("/");
            return;
        }
        boolean text = "text".equalsIgnoreCase(format);
        IncidentSnapshot snapshot = snapshotService.collect();

        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(text ? "text/plain" : "application/json");
        response.setHeader("Content-Disposition",
                "attachment; filename=\"" + fileName(snapshot.connection(), text ? "txt" : "json") + "\"");
        if (text)
        {
            SnapshotWriter.writeText(response.getWriter(), snapshot);
        }
        else
        {
            SnapshotWriter.writeJson(response.getWriter(), snapshot);
        }
    }

    /** Host and port go into a Content-Disposition filename, so anything outside a safe set is replaced. */
    static String fileName(ConnectionInfo connection, String extension)
    {
        String broker = connection == null ? "broker" : connection.host() + "-" + connection.port();
        String safe = broker.replaceAll("[^A-Za-z0-9._-]", "_");
        return "incident-" + safe + "-" + LocalDateTime.now().format(FILE_STAMP) + "." + extension;
    }
}
