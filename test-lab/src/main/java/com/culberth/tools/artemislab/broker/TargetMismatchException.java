package com.culberth.tools.artemislab.broker;

import com.culberth.tools.artemislab.LabException;

/** A connection reached a broker other than the one the lab owns. Nothing is sent on it. */
public class TargetMismatchException extends LabException
{

    public TargetMismatchException(String expected, String actual)
    {
        super("Refused: this connection reached broker node " + actual + ", not the lab's broker " + expected
                + ". Nothing was sent. If the lab broker was replaced, provision it again rather than reusing this run.");
    }
}
