package com.culberth.tools.artemisbrowser.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Serves the sign-in page. The sign-in itself is handled by Spring Security, not by a method here. */
@Controller
public class LoginController
{

    private final boolean openLocally;

    public LoginController(@Value("${server.address:}") String bindAddress,
            @Value("${artemis.auth.username:}") String username,
            @Value("${artemis.auth.password-hash:}") String passwordHash)
    {
        this.openLocally = SecurityConfig.openLocally(bindAddress, username, passwordHash);
    }

    /** With no login configured on loopback there is nothing to sign in to, so a bookmark to it lands on the app. */
    @GetMapping("/login")
    public String login()
    {
        return openLocally ? "redirect:/" : "login";
    }
}
