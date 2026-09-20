package com.culberth.tools.artemisbrowser.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the sign-in page. The sign-in itself is handled by Spring Security, not by a method here. */
@Controller
public class LoginController
{

    @GetMapping("/login")
    public String login()
    {
        return "login";
    }
}
