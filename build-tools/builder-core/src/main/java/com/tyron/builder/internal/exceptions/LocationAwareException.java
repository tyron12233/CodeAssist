package com.tyron.builder.internal.exceptions;


import com.tyron.builder.api.internal.exceptions.FailureResolutionAware;
import com.tyron.builder.groovy.scripts.ScriptSource;

import org.apache.commons.lang3.StringUtils;

/**
 * A {@code LocationAwareException} is an exception which can be annotated with a location in a script.
 */
//@UsedByScanPlugin
public class LocationAwareException extends ContextAwareException implements FailureResolutionAware {
    private final String sourceDisplayName;
    private final Integer lineNumber;

    public LocationAwareException(Throwable cause, ScriptSource source, Integer lineNumber) {
        this(cause, source != null ? source.getDisplayName() : null, lineNumber);
    }

    public LocationAwareException(Throwable cause, String sourceDisplayName, Integer lineNumber) {
        super(cause);
        this.sourceDisplayName = sourceDisplayName;
        this.lineNumber = lineNumber;
    }

    /**
     * <p>Returns the display name of the script where this exception occurred.</p>
     *
     * @return The source display name. May return null.
     */
    public String getSourceDisplayName() {
        return sourceDisplayName;
    }

    /**
     * <p>Returns a description of the location of where this exception occurred.</p>
     *
     * @return The location description. May return null.
     */
    public String getLocation() {
        if (sourceDisplayName == null) {
            return null;
        }
        String sourceMsg = StringUtils.capitalize(sourceDisplayName);
        if (lineNumber == null) {
            return sourceMsg;
        }
        return String.format("%s line: %d", sourceMsg, lineNumber);
    }

    /**
     * Returns the line in the script where this exception occurred, if known.
     *
     * @return The line number, or null if not known.
     */
    public Integer getLineNumber() {
        return lineNumber;
    }

    /**
     * Returns the fully formatted error message, including the location.
     *
     * @return the message. May return null.
     */
    @Override
    public String getMessage() {
        String location = getLocation();
        String message = getCause().getMessage();
        if (location == null && message == null) {
            return null;
        }
        if (location == null) {
            return message;
        }
        if (message == null) {
            return location;
        }
        return String.format("%s%n%s", location, message);
    }

    @Override
    public void appendResolutions(Context context) {
        if (getCause() instanceof FailureResolutionAware) {
            FailureResolutionAware resolutionAware = (FailureResolutionAware) getCause();
            resolutionAware.appendResolutions(context);
        }
    }

    @Override
    public void accept(ExceptionContextVisitor contextVisitor) {
        super.accept(contextVisitor);
        String location = getLocation();
        if (location != null) {
            contextVisitor.visitLocation(location);
        }
    }
}