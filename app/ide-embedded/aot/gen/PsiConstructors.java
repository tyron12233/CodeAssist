import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/** Every concrete PSI element class in the given jars with its public constructors, as reachability metadata. */
public class PsiConstructors {
    public static void main(String[] args) throws Exception {
        ClassLoader loader = PsiConstructors.class.getClassLoader();
        Class<?> psi = Class.forName("com.intellij.psi.PsiElement", false, loader);
        Class<?> ast = Class.forName("com.intellij.lang.ASTNode", false, loader);
        Class<?> stub = Class.forName("com.intellij.psi.stubs.StubElement", false, loader);
        List<String> entries = new ArrayList<>();
        int classes = 0;
        for (int i = 1; i < args.length; i++) {
            try (JarFile jar = new JarFile(args[i])) {
                for (JarEntry e : Collections.list(jar.entries())) {
                    String n = e.getName();
                    if (!n.endsWith(".class") || n.contains("module-info") || n.startsWith("META-INF")) continue;
                    if (!(n.startsWith("org/jetbrains/kotlin/") || n.startsWith("com/intellij/"))) continue;
                    Class<?> c;
                    try { c = Class.forName(n.substring(0, n.length() - 6).replace('/', '.'), false, loader); } catch (Throwable t) { continue; }
                    try {
                        if (c.isInterface() || Modifier.isAbstract(c.getModifiers()) || !(psi.isAssignableFrom(c) || ast.isAssignableFrom(c))) continue;
                        List<String> ctors = new ArrayList<>();
                        for (Constructor<?> k : c.getDeclaredConstructors()) {
                            Class<?>[] p = k.getParameterTypes();
                            if (p.length == 0 || p.length > 2) { if (p.length != 0) continue; }
                            boolean wanted = p.length == 0 || ast.isAssignableFrom(p[0]) || stub.isAssignableFrom(p[0]) || p[0].getName().equals("com.intellij.psi.tree.IElementType");
                            if (!wanted) continue;
                            StringBuilder s = new StringBuilder("{\"name\":\"<init>\",\"parameterTypes\":[");
                            for (int j = 0; j < p.length; j++) s.append(j > 0 ? "," : "").append('"').append(p[j].getName()).append('"');
                            ctors.add(s.append("]}").toString());
                        }
                        if (ctors.isEmpty()) continue;
                        classes++;
                        entries.add("    {\"type\":\"" + c.getName() + "\",\"methods\":[" + String.join(",", ctors) + "]}");
                    } catch (Throwable t) { }
                }
            }
        }
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(Path.of(args[0])))) {
            out.println("{\n  \"reflection\": [");
            out.println(String.join(",\n", entries));
            out.println("  ]\n}");
        }
        System.out.println(classes + " classes");
    }
}
