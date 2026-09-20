package com.culberth.tools.artemisbrowser.web;

import com.culberth.tools.artemisbrowser.broker.BrokerException;
import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.Finding;
import com.culberth.tools.artemisbrowser.broker.StuckDiagnosisService;
import java.util.List;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/** The "why is this not moving" page. */
@Controller
public class DiagnoseController
{

    private final BrokerSession brokerSession;
    private final StuckDiagnosisService diagnosis;

    public DiagnoseController(BrokerSession brokerSession, StuckDiagnosisService diagnosis)
    {
        this.brokerSession = brokerSession;
        this.diagnosis = diagnosis;
    }

    @GetMapping("/diagnose")
    public String diagnose(@RequestParam(name = "internal", defaultValue = "false") boolean internal, Model model)
    {

        if (!brokerSession.isConnected())
        {
            return "redirect:/";
        }
        model.addAttribute("connection", brokerSession.info());
        model.addAttribute("internal", internal);
        try
        {
            List<Finding> findings = diagnosis.diagnose(internal);
            model.addAttribute("findings", findings);
            model.addAttribute("stuckCount", findings.stream().filter(Finding::isStuck).count());
        }
        catch (BrokerException e)
        {
            model.addAttribute("findings", List.of());
            model.addAttribute("stuckCount", 0L);
            model.addAttribute("error", e.getMessage());
        }
        return "diagnose";
    }
}
