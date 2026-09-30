package com.culberth.tools.artemisbrowser.web;

import com.culberth.tools.artemisbrowser.broker.BrokerSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * What each request cost the broker: management round trips and time, logged at DEBUG.
 *
 * <p>
 * Off unless {@code logging.level.com.culberth.tools.artemisbrowser.web.ManagementCallLog=DEBUG}. It is how the page
 * costs in the README were measured, kept so they can be measured again. It reads the broker session only when the
 * request already has an HTTP session: the session-scoped bean would otherwise create one, and a probe fetching
 * {@code /app.css} every few seconds would leave a session behind each time.
 */
@Configuration
public class ManagementCallLog implements WebMvcConfigurer
{

    private static final Logger LOG = LoggerFactory.getLogger(ManagementCallLog.class);
    private static final String START = ManagementCallLog.class.getName() + ".start";

    private final ObjectProvider<BrokerSession> brokerSession;

    public ManagementCallLog(ObjectProvider<BrokerSession> brokerSession)
    {
        this.brokerSession = brokerSession;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry)
    {
        registry.addInterceptor(new HandlerInterceptor()
        {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            {
                if (LOG.isDebugEnabled() && request.getSession(false) != null)
                {
                    request.setAttribute(START, new long[]
                    { System.nanoTime(), brokerSession.getObject().managementCalls()
                    });
                }
                return true;
            }

            @Override
            public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler,
                    Exception ex)
            {
                if (request.getAttribute(START) instanceof long[] start)
                {
                    LOG.debug("{} {}: {} management call(s), {}ms", request.getMethod(), request.getRequestURI(),
                            brokerSession.getObject().managementCalls() - start[1],
                            (System.nanoTime() - start[0]) / 1_000_000);
                }
            }
        });
    }
}
