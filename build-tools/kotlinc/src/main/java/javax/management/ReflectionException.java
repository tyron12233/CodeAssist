package javax.management;

public class ReflectionException extends JMException {
    private static final long serialVersionUID = 9170809325636915553L;
    private Exception exception;

    public ReflectionException(Exception e) {
        this.exception = e;
    }

    public ReflectionException(Exception e, String message) {
        super(message);
        this.exception = e;
    }

    public Exception getTargetException() {
        return this.exception;
    }

    public Throwable getCause() {
        return this.exception;
    }
}
