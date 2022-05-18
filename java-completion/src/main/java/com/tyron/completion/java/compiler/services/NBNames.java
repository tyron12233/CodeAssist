package com.tyron.completion.java.compiler.services;

import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.util.Names;

/**
 *
 * @author lahvac
 */
public class NBNames {

    public static final Context.Key<NBNames> nbNamesKey = new Context.Key<>();

    public static void preRegister(Context context) {
        context.put(nbNamesKey, (Context.Factory<NBNames>) NBNames::new);
    }

    public static NBNames instance(Context context) {
        NBNames instance = context.get(nbNamesKey);
        if (instance == null) {
            instance = new NBNames(context);
        }
        return instance;
    }


    public final Name _org_netbeans_EnclosingMethod;
    public final Name _org_netbeans_TypeSignature;
    public final Name _org_netbeans_ParameterNames;
    public final Name _org_netbeans_SourceLevelAnnotations;
    public final Name _org_netbeans_SourceLevelParameterAnnotations;
    public final Name _org_netbeans_SourceLevelTypeAnnotations;

    protected NBNames(Context context) {
        Names n = Names.instance(context);

        _org_netbeans_EnclosingMethod = n.fromString("org.netbeans.EnclosingMethod");
        _org_netbeans_TypeSignature = n.fromString("org.netbeans.TypeSignature");
        _org_netbeans_ParameterNames = n.fromString("org.netbeans.ParameterNames");
        _org_netbeans_SourceLevelAnnotations = n.fromString("org.netbeans.SourceLevelAnnotations");
        _org_netbeans_SourceLevelParameterAnnotations = n.fromString("org.netbeans.SourceLevelParameterAnnotations");
        _org_netbeans_SourceLevelTypeAnnotations = n.fromString("org.netbeans.SourceLevelTypeAnnotations");
    }

}