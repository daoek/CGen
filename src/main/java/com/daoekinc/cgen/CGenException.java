package com.daoekinc.cgen;

public class CGenException extends RuntimeException {
    private final String helpTitle;
    private final String helpText;

    public CGenException(String message) {
        super(message);
        helpTitle = null;
        helpText = null;
    }

    public CGenException(String message, Throwable cause) {
        super(message, cause);
        helpTitle = null;
        helpText = null;
    }

    public CGenException(String message, String helpTitle, String helpText) {
        super(message);
        this.helpTitle = helpTitle;
        this.helpText = helpText;
    }

    public String helpTitle() {
        return helpTitle;
    }

    public String helpText() {
        return helpText;
    }
}
