package com.culberth.tools.artemisbrowser.web;

import java.security.Principal;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/**
 * Puts the signed-in username on every page, so the nav can say whose session this is.
 *
 * <p>
 * Done with the servlet's own {@link Principal} rather than by adding Thymeleaf's Spring Security dialect: one name on
 * one bar does not justify another dependency on the render path.
 */
@ControllerAdvice
public class CurrentUserAdvice
{

    @ModelAttribute("currentUser")
    String currentUser(Principal principal)
    {
        return principal == null ? null : principal.getName();
    }
}
