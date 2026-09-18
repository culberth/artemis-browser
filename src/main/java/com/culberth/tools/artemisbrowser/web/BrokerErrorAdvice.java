package com.culberth.tools.artemisbrowser.web;

import com.culberth.tools.artemisbrowser.broker.NotConnectedException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

/** A dropped or never-established connection sends the user back to the connect form. */
@ControllerAdvice
public class BrokerErrorAdvice
{

    @ExceptionHandler(NotConnectedException.class)
    public String handleNotConnected()
    {
        return "redirect:/";
    }
}
