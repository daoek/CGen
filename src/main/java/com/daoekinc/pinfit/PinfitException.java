package com.daoekinc.pinfit;

public class PinfitException extends RuntimeException {
    private final String helpTitle;
    private final String helpText;

    public PinfitException(String message) {
        super(message);
        helpTitle = null;
        helpText = null;
    }

    public PinfitException(String message, Throwable cause) {
        super(message, cause);
        helpTitle = null;
        helpText = null;
    }

    public PinfitException(String message, String helpTitle, String helpText) {
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
