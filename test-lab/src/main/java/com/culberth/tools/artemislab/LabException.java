package com.culberth.tools.artemislab;

/**
 * An action the lab refused or could not complete, worded for the person at the page. Controllers show the message;
 * nothing else about it is secret or internal.
 */
public class LabException extends RuntimeException
{

    public LabException(String message)
    {
        super(message);
    }

    public LabException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
