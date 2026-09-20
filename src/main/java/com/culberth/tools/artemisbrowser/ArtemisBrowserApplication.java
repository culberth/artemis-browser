package com.culberth.tools.artemisbrowser;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootApplication
public class ArtemisBrowserApplication
{

    private static final String HASH_PASSWORD = "--hash-password=";

    public static void main(String[] args)
    {
        for (String arg : args)
        {
            if (arg.startsWith(HASH_PASSWORD))
            {
                // Generating the hash needs bcrypt, which is already on the classpath, so the app
                // can do it rather than sending someone to find a tool that does. Nothing starts:
                // this prints and exits, so the password never reaches a running server or a log.
                System.out.println(new BCryptPasswordEncoder().encode(arg.substring(HASH_PASSWORD.length())));
                return;
            }
        }
        SpringApplication.run(ArtemisBrowserApplication.class, args);
    }
}
