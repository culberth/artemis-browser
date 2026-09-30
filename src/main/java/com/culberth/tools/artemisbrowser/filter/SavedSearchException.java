package com.culberth.tools.artemisbrowser.filter;

/** A saved search could not be saved, renamed or deleted; the message says why in words fit to show. */
public class SavedSearchException extends RuntimeException
{

    public SavedSearchException(String message)
    {
        super(message);
    }

    public SavedSearchException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
