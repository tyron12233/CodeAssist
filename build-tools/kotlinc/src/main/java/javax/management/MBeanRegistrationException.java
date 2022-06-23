package javax.management;

public class MBeanRegistrationException extends MBeanException {
    private static final long serialVersionUID = 4482382455277067805L;

    public MBeanRegistrationException(Exception e) {
        super(e);
    }

    public MBeanRegistrationException(Exception e, String message) {
        super(e, message);
    }
}
