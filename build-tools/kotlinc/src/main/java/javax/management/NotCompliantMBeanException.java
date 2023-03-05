package javax.management;

public class NotCompliantMBeanException extends OperationsException {
    private static final long serialVersionUID = 5175579583207963577L;

    public NotCompliantMBeanException() {
    }

    public NotCompliantMBeanException(String message) {
        super(message);
    }
}
