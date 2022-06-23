package javax.management;

public class MBeanException extends JMException {
    private static final long serialVersionUID = 4066342430588744142L;
    private Exception exception;

    public MBeanException(Exception e) {
        this.exception = e;
    }

    public MBeanException(Exception e, String message) {
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
