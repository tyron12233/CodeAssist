package com.tyron.completion.java.modules;

import com.sun.source.util.JavacTask;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.code.Kinds.Kind;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.CompletionFailure;
import com.sun.tools.javac.code.Symbol.ModuleSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.comp.Check;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;
import java.util.Set;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.TypeElement;

/** TODO
 *
 * @author lahvac
 */
public class ElementUtils {

//    public static TypeElement getTypeElementByBinaryName(CompilationInfo info, String name) {
//        return getTypeElementByBinaryName(CompilationInfoAccessor.getInstance().getJavacTask(info), name);
//    }

    public static TypeElement getTypeElementByBinaryName(JavacTask task, String name) {
        Set<? extends ModuleElement> allModules = task.getElements().getAllModuleElements();
        Context ctx = ((JavacTaskImpl) task).getContext();
        Symtab syms = Symtab.instance(ctx);
        
        if (allModules.isEmpty()) {
            return getTypeElementByBinaryName(task, syms.noModule, name);
        }
        
        TypeElement result = null;
        boolean foundInUnamedModule = false;
        
        for (ModuleElement me : allModules) {
            TypeElement found = getTypeElementByBinaryName(task, me, name);

            if (result == found) {
                // avoid returning null, partial fix for [NETBEANS-4832]
                continue;
            }

            if (found != null) {
                if ((ModuleSymbol) me == syms.unnamedModule) {
                    foundInUnamedModule = true;
                }
                if (result != null) {
                    if (foundInUnamedModule == true) {
                        for (TypeElement elem : new TypeElement[]{result, found}) {
                            if ((elem.getKind().isClass() || elem.getKind().isInterface())
                                    && (((ClassSymbol) elem).packge().modle != syms.unnamedModule)) {
                                return elem;
                            }
                        }
                    } else {
                        return null;
                    }
                }
                result = found;
            }
        }
        
        return result;
    }

//    public static TypeElement getTypeElementByBinaryName(ClassSymbolompilationInfo info, ModuleElement mod, String name) {
//        return getTypeElementByBinaryName(CompilationInfoAccessor.getInstance().getJavacTask(info), mod, name);
//    }

    public static TypeElement getTypeElementByBinaryName(JavacTask task, ModuleElement mod, String name) {
        Context ctx = ((JavacTaskImpl) task).getContext();
        Names names = Names.instance(ctx);
        Symtab syms = Symtab.instance(ctx);
        Check chk = Check.instance(ctx);
        final Name wrappedName = names.fromString(name);
        ClassSymbol clazz = chk.getCompiled((ModuleSymbol) mod, wrappedName);
        if (clazz != null) {
            return clazz;
        }
        clazz = syms.enterClass((ModuleSymbol) mod, wrappedName);
        
        try {
            clazz.complete();
            
            if (clazz.kind == Kind.TYP &&
                clazz.flatName() == wrappedName) {
                return clazz;
            }
        } catch (CompletionFailure cf) {
        }

        return null;
    }
}