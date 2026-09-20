package com.culberth.tools.artemisbrowser.web;

import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import com.culberth.tools.artemisbrowser.broker.ConnectionLostException;
import com.culberth.tools.artemisbrowser.broker.NotConnectedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** Sends a request that cannot be served back to the connect form, saying why when there is a why worth saying. */
@ControllerAdvice
public class BrokerErrorAdvice
{

    private final BrokerSession brokerSession;

    public BrokerErrorAdvice(BrokerSession brokerSession)
    {
        this.brokerSession = brokerSession;
    }

    /** Never connected, or the HTTP session expired and took the connection with it. */
    @ExceptionHandler(NotConnectedException.class)
    public String handleNotConnected()
    {
        return "redirect:/";
    }

    /**
     * Connected a moment ago, and now not.
     *
     * <p>
     * The dead connection is closed rather than left in place: without that the app goes on believing it is connected,
     * and every page answers with a broker error that reads like a bug in this tool. Closing it means the next request
     * is an honest "not connected", and the connect form is one retyped password away.
     */
    @ExceptionHandler(ConnectionLostException.class)
    public String handleConnectionLost(ConnectionLostException e, RedirectAttributes redirect)
    {
        String where = brokerSession.info() == null ? "the broker"
                : brokerSession.info().host() + ":" + brokerSession.info().port();
        brokerSession.close();
        redirect.addFlashAttribute("lostConnection",
                "The connection to " + where + " was lost — the broker may have restarted, or the network"
                        + " dropped. Connect again to carry on; nothing was changed on the broker.");
        return "redirect:/";
    }
}
