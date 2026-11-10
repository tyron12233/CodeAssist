package dev.ide.jvm.fixtures;

import java.io.IOException;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.List;

/** `java.lang.reflect.Proxy` over interfaces the VM interprets: how a test fakes an interface (a plugin test
 *  faking its registration) instead of implementing it. Each method's result is compared with running it for real. */
public final class ReflectProxy {
    private ReflectProxy() {}

    public interface Named { String name(); }

    public interface Registration extends Named {
        int register(String id, int weight);
        long total(long base, double factor, boolean twice);
        void log(String line);
        List<String> ids();
        default String describe() { return "registration " + name(); }
    }

    public interface Reads { String read(String path) throws IOException; }

    static final class Recorder implements InvocationHandler {
        final StringBuilder calls = new StringBuilder();

        @Override public Object invoke(Object proxy, Method method, Object[] args) {
            calls.append(method.getName()).append(args == null ? "()" : "/" + args.length).append(';');
            switch (method.getName()) {
                case "name": return "fake";
                case "register": return ((String) args[0]).length() + (Integer) args[1];
                case "total": return (long) ((Long) args[0] * (Double) args[1] * ((Boolean) args[2] ? 2 : 1));
                case "ids": { List<String> l = new ArrayList<>(); l.add("a"); l.add("b"); return l; }
                case "describe": return "described";
                case "toString": return "Recorder proxy";
                case "hashCode": return 42;
                case "equals": return proxy == args[0];
                default: return null;
            }
        }
    }

    /** Every method, primitives boxed and unboxed, default and Object methods routed through the handler. */
    public static String routesEveryMethod() {
        Recorder h = new Recorder();
        Registration r = (Registration) Proxy.newProxyInstance(
            Registration.class.getClassLoader(), new Class<?>[] {Registration.class}, h);
        String out = r.name() + "," + r.register("abc", 4) + "," + r.total(10L, 1.5, true) + "," + r.ids()
            + "," + r.describe() + "," + r + "," + r.hashCode() + "," + r.equals(r) + "," + r.equals("x");
        r.log("line");
        return out + "|" + h.calls + "|" + (r instanceof Named) + "|" + (Proxy.getInvocationHandler(r) == h);
    }

    /** The handler is a lambda and gets a Method it can read and invoke on a real implementation. */
    public static String delegatesThroughMethod() {
        Named real = () -> "real";
        Named n = (Named) Proxy.newProxyInstance(Named.class.getClassLoader(), new Class<?>[] {Named.class},
            (proxy, method, args) -> method.getDeclaringClass().getSimpleName() + "." + method.getName() + "="
                + method.invoke(real, args));
        return n.name();
    }

    /** An interpreted interface next to a real one: the proxy is both. */
    public static String mixesWithARealInterface() {
        StringBuilder ran = new StringBuilder();
        Object p = Proxy.newProxyInstance(Named.class.getClassLoader(), new Class<?>[] {Named.class, Runnable.class},
            (proxy, method, args) -> { ran.append(method.getName()); return method.getName().equals("name") ? "n" : null; });
        ((Runnable) p).run();
        return ((Named) p).name() + "," + ran;
    }

    /** A declared checked exception passes through; an undeclared one is wrapped; unchecked ones pass. */
    public static String exceptions() {
        Reads reads = (Reads) Proxy.newProxyInstance(Reads.class.getClassLoader(), new Class<?>[] {Reads.class},
            (proxy, method, args) -> {
                if ("io".equals(args[0])) throw new IOException("no file");
                if ("state".equals(args[0])) throw new IllegalStateException("bad");
                throw new Exception("checked");
            });
        Named named = (Named) Proxy.newProxyInstance(Named.class.getClassLoader(), new Class<?>[] {Named.class},
            (proxy, method, args) -> { throw new IOException("undeclared"); });
        StringBuilder out = new StringBuilder();
        for (String a : new String[] {"io", "state", "other"}) {
            try { reads.read(a); } catch (Throwable t) { out.append(t.getClass().getSimpleName()).append(':').append(t.getMessage()).append(','); }
        }
        try { named.name(); } catch (UndeclaredThrowableException e) { out.append("Undeclared:").append(e.getCause().getMessage()); }
        return out.toString();
    }

    /** A null for a primitive result is a NullPointerException; a class that is not an interface is refused. */
    public static String misuse() {
        Registration r = (Registration) Proxy.newProxyInstance(Registration.class.getClassLoader(),
            new Class<?>[] {Registration.class}, (proxy, method, args) -> null);
        StringBuilder out = new StringBuilder();
        try { r.register("x", 1); out.append("no NPE"); } catch (NullPointerException e) { out.append("NPE"); }
        try {
            Proxy.newProxyInstance(ReflectProxy.class.getClassLoader(), new Class<?>[] {Recorder.class}, (p, m, a) -> null);
            out.append(",accepted");
        } catch (IllegalArgumentException e) {
            out.append(",refused");
        }
        return out.toString();
    }
}
