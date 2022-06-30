package com.tyron.completion.java.compiler.services;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.TypeSymbol;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.comp.Resolve;
import com.sun.tools.javac.util.Context;

/**
 *
 * @author lahvac
 */
public class NBResolve extends Resolve {
    public static NBResolve instance(Context context) {
        Resolve instance = context.get(resolveKey);
        if (instance == null) {
            instance = new NBResolve(context);
        }
        return (NBResolve) instance;
    }

    public static void preRegister(Context context) {
        context.put(resolveKey, (Context.Factory<Resolve>) NBResolve::new);
    }

    protected NBResolve(Context ctx) {
        super(ctx);
    }

    private boolean accessibleOverride;
    
    public void disableAccessibilityChecks() {
        accessibleOverride = true;
    }
    
    public void restoreAccessbilityChecks() {
        accessibleOverride = false;
    }
    
    @Override
    public boolean isAccessible(Env<AttrContext> env, Type site, Symbol sym, boolean checkInner) {
        if (accessibleOverride) {
            return true;
        }
        return super.isAccessible(env, site, sym, checkInner);
    }

    @Override
    public boolean isAccessible(Env<AttrContext> env, TypeSymbol c, boolean checkInner) {
        if (accessibleOverride) {
            return true;
        }
        return super.isAccessible(env, c, checkInner);
    }

    public static boolean isStatic(Env<AttrContext> env) {
        return Resolve.isStatic(env);
    }
}