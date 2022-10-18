package com.tyron.lint.parser;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.common.collect.Lists;
import com.google.common.collect.MapMaker;
import com.google.common.collect.Maps;
import com.tyron.builder.project.api.Module;
import com.tyron.lint.api.JavaContext;
import com.tyron.lint.client.JavaParser;
import com.tyron.lint.client.LintClient;

import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ICompilerRequestor;
import org.eclipse.jdt.internal.compiler.IErrorHandlingPolicy;
import org.eclipse.jdt.internal.compiler.IProblemFactory;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.batch.FileSystem;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.lookup.Binding;
import org.eclipse.jdt.internal.compiler.lookup.LookupEnvironment;
import org.eclipse.jdt.internal.compiler.lookup.ReferenceBinding;
import org.eclipse.jdt.internal.compiler.parser.Parser;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;
import org.jetbrains.kotlin.com.intellij.psi.PsiJavaFile;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class EcjParser extends JavaParser {

    private static final boolean DEBUG_DUMP_PARSE_ERRORS = false;
    private static final boolean KEEP_LOOKUP_ENVIRONMENT = false;

    private final Module mModule;
    private Map<File, EcjSourceFile> mSourceUnits;
    private Parser mParser;

    public EcjParser(Module module) {
        mModule = module;
    }

    public static CompilerOptions createCompilerOptions() {
        CompilerOptions options = new CompilerOptions();

        long languageLevel = ClassFileConstants.JDK1_8;
        options.complianceLevel = languageLevel;
        options.sourceLevel = languageLevel;
        options.targetJDK = languageLevel;
        options.originalComplianceLevel = languageLevel;
        options.originalSourceLevel = languageLevel;
        options.inlineJsrBytecode = true;
        options.parseLiteralExpressionsAsConstants = true;
        options.analyseResourceLeaks = false;
        options.docCommentSupport = false;
        options.defaultEncoding = "utf-8";
        options.suppressOptionalErrors = true;
        options.generateClassFiles = false;
        options.isAnnotationBasedNullAnalysisEnabled = false;
        options.reportUnusedDeclaredThrownExceptionExemptExceptionAndThrowable = false;
        options.reportUnusedDeclaredThrownExceptionIncludeDocCommentReference = false;
        options.reportUnusedDeclaredThrownExceptionWhenOverriding = false;
        options.reportUnusedParameterIncludeDocCommentReference = false;
        options.reportUnusedParameterWhenImplementingAbstract = false;
        options.reportUnusedParameterWhenOverridingConcrete = false;
        options.suppressWarnings = true;
        options.processAnnotations = true;
        options.storeAnnotations = true;
        options.verbose = false;
        return options;
    }

    private Parser getParser() {
        if (mParser == null) {
            CompilerOptions options = createCompilerOptions();
            ProblemReporter problemReporter = new ProblemReporter(
                    DefaultErrorHandlingPolicies.exitOnFirstError(),
                    options,
                    new DefaultProblemFactory());
            mParser = new Parser(problemReporter,
                    options.parseLiteralExpressionsAsConstants);
            mParser.javadocParser.checkDocComment = false;
        }
        return mParser;
    }

    @Override
    public void prepareJavaParse(@NonNull List<JavaContext> contexts) {
        if (mModule == null || contexts.isEmpty()) {
            return;
        }

        List<EcjSourceFile> sources = Lists.newArrayListWithExpectedSize(contexts.size());
        mSourceUnits = Maps.newHashMapWithExpectedSize(sources.size());
        for (JavaContext context : contexts) {
            String contents = context.getContents();
            if (contents == null) {
                continue;
            }
            File file = context.file;
            EcjSourceFile unit = new EcjSourceFile(contents, file);
            sources.add(unit);
            mSourceUnits.put(file, unit);
        }
        List<String> classPath = Collections.emptyList();//computeClassPath(contexts);
        try {

        } catch (Throwable t) {

        }
    }

    @Nullable
    @Override
    public PsiJavaFile parseJavaToPsi(@NonNull JavaContext context) {
        return null;
    }

    /** Parse the given source units and class path and store it into the given output map */
    @NonNull
    public static EcjResult parse(
            CompilerOptions options,
            @NonNull List<EcjSourceFile> sourceUnits,
            @NonNull List<String> classPath,
            @Nullable LintClient client) {
        Map<EcjSourceFile, CompilationUnitDeclaration> outputMap =
                Maps.newHashMapWithExpectedSize(sourceUnits.size());

        INameEnvironment environment = new FileSystem(
                classPath.toArray(new String[classPath.size()]), new String[0],
                options.defaultEncoding);
        IErrorHandlingPolicy policy = DefaultErrorHandlingPolicies.proceedWithAllProblems();
        IProblemFactory problemFactory = new DefaultProblemFactory(Locale.getDefault());
        ICompilerRequestor requestor = result -> {
            // Not used; we need the corresponding CompilationUnitDeclaration for the source
            // units (the AST parsed from source) which we don't get access to here, so we
            // instead subclass AST to get our hands on them.
        };

        NonGeneratingCompiler compiler = new NonGeneratingCompiler(environment, policy, options,
                requestor, problemFactory, outputMap);
        try {
            compiler.compile(sourceUnits.toArray(new ICompilationUnit[0]));
        } catch (OutOfMemoryError e) {
            environment.cleanup();


            // Since we're running out of memory, if it's all still held we could potentially
            // fail attempting to log the failure. Actively get rid of the large ECJ data
            // structure references first so minimize the chance of that
            //noinspection UnusedAssignment
            compiler = null;
            //noinspection UnusedAssignment
            environment = null;
            //noinspection UnusedAssignment
            requestor = null;
            //noinspection UnusedAssignment
            problemFactory = null;
            //noinspection UnusedAssignment
            policy = null;
        } catch (Throwable t) {
            environment.cleanup();
            environment = null;
        }

        LookupEnvironment lookupEnvironment = compiler != null ? compiler.lookupEnvironment : null;
        return new EcjResult(environment, lookupEnvironment, outputMap);
    }

    public static class EcjResult {
        @Nullable private final INameEnvironment mNameEnvironment;
        @Nullable private final LookupEnvironment mLookupEnvironment;
        @NonNull  private final Map<EcjSourceFile, CompilationUnitDeclaration> mSourceToUnit;
        @Nullable private Map<ICompilationUnit, PsiJavaFile> mPsiMap;
        @Nullable private Map<CompilationUnitDeclaration, EcjSourceFile> mUnitToSource;
        @Nullable Map<Binding, CompilationUnitDeclaration> mBindingToUnit;

        public EcjResult(@Nullable INameEnvironment nameEnvironment,
                         @Nullable LookupEnvironment lookupEnvironment,
                         @NonNull Map<EcjSourceFile, CompilationUnitDeclaration> compilationUnits) {
            mNameEnvironment = nameEnvironment;
            mLookupEnvironment = lookupEnvironment;
            mSourceToUnit = compilationUnits;
        }

        @Nullable
        public LookupEnvironment getLookupEnvironment() {
            return mLookupEnvironment;
        }

        @Nullable
        public PsiJavaFile findFile(
                @NonNull EcjSourceFile sourceUnit,
                @Nullable String source) {
            if (mPsiMap != null) {
                PsiJavaFile file = mPsiMap.get(sourceUnit);
                if (file != null) {
                    return file;
                }
            } else {
                // Using weak values to allow map to occasionally refresh
                mPsiMap = new MapMaker()
                        .initialCapacity(mSourceToUnit.size())
                        .weakValues()
                        .concurrencyLevel(1)
                        .makeMap();
            }
            CompilationUnitDeclaration unit = getCompilationUnit(sourceUnit);
            if (unit != null) {
//                PsiJavaFile file = EcjPsiBuilder.create(mPsiManager, unit, sourceUnit);
//                assert mPsiMap != null;
//                mPsiMap.put(sourceUnit, file);
//                return file;
            }
            return null;
        }

        @Nullable
        public PsiJavaFile findFileContaining(@Nullable ReferenceBinding declaringClass) {
            if (mUnitToSource == null) {
                int size = mSourceToUnit.size();
                mUnitToSource = Maps.newHashMapWithExpectedSize(size);
                mBindingToUnit = Maps.newHashMapWithExpectedSize(size);
                for (Map.Entry<EcjSourceFile, CompilationUnitDeclaration> entry
                        : mSourceToUnit.entrySet()) {
                    CompilationUnitDeclaration unit = entry.getValue();
                    EcjSourceFile sourceUnit = entry.getKey();
                    mUnitToSource.put(unit, sourceUnit);
                    for (TypeDeclaration declaration : unit.types) {
                        recordTypeAssociation(mBindingToUnit, declaration, unit);
                    }
                }
            }
            assert mBindingToUnit != null;
            while (declaringClass != null) {
                CompilationUnitDeclaration unit = mBindingToUnit.get(declaringClass);
                if (unit != null) {
                    EcjSourceFile sourceUnit = mUnitToSource.get(unit);
                    if (sourceUnit != null) {
                        return findFile(sourceUnit, null);
                    }
                }
                declaringClass = declaringClass.enclosingType();
            }
            return null;
        }

        private static void recordTypeAssociation(
                @NonNull Map<Binding, CompilationUnitDeclaration> bindingMap,
                @NonNull TypeDeclaration declaration,
                @NonNull CompilationUnitDeclaration unit) {
            bindingMap.put(declaration.binding, unit);
            if (declaration.memberTypes != null) {
                for (TypeDeclaration d : declaration.memberTypes) {
                    recordTypeAssociation(bindingMap, d, unit);
                }
            }
        }

        /**
         * Returns the collection of compilation units found by the parse task
         *
         * @return a read-only collection of compilation units
         */
        @NonNull
        public Collection<CompilationUnitDeclaration> getCompilationUnits() {
            return mSourceToUnit.values();
        }
        /**
         * Returns the compilation unit parsed from the given source unit, if any
         *
         * @param sourceUnit the original source passed to ECJ
         * @return the corresponding compilation unit, if created
         */
        @Nullable
        public CompilationUnitDeclaration getCompilationUnit(
                @NonNull EcjSourceFile sourceUnit) {
            return mSourceToUnit.get(sourceUnit);
        }

        /**
         * Removes the compilation unit for the given source unit, if any. Used when individual
         * source units are disposed to allow memory to be freed up.
         *
         * @param sourceUnit the source unit
         */
        void removeCompilationUnit(@NonNull EcjSourceFile sourceUnit) {
            mSourceToUnit.remove(sourceUnit);
        }
        /**
         * Disposes this parser result, allowing various ECJ data structures to be freed up even if
         * the parser instance hangs around.
         */
        public void dispose() {
            if (mNameEnvironment != null) {
                mNameEnvironment.cleanup();
            }
            if (mLookupEnvironment != null) {
                mLookupEnvironment.reset();
            }
            mSourceToUnit.clear();
        }
    }

    // Custom version of the compiler which skips code generation and records source units
    private static class NonGeneratingCompiler extends Compiler {
        private Map<EcjSourceFile, CompilationUnitDeclaration> mUnits;
        private CompilationUnitDeclaration mCurrentUnit;
        public NonGeneratingCompiler(INameEnvironment environment, IErrorHandlingPolicy policy,
                                     CompilerOptions options, ICompilerRequestor requestor,
                                     IProblemFactory problemFactory,
                                     Map<EcjSourceFile, CompilationUnitDeclaration> units) {
            super(environment, policy, options, requestor, problemFactory, null, null);
            mUnits = units;
        }
        @Nullable
        CompilationUnitDeclaration getCurrentUnit() {
            // Can't use mLookupEnvironment.unitBeingCompleted directly; it gets nulled out
            // as part of the exception catch handling in the compiler before this method
            // is called from lint -- therefore we stash a copy in our own mCurrentUnit field
            return mCurrentUnit;
        }
        @Override
        protected synchronized void addCompilationUnit(ICompilationUnit sourceUnit,
                                                       CompilationUnitDeclaration parsedUnit) {
            super.addCompilationUnit(sourceUnit, parsedUnit);
            mUnits.put((EcjSourceFile)sourceUnit, parsedUnit);
        }
        @Override
        public void process(CompilationUnitDeclaration unit, int unitNumber) {
            mCurrentUnit = lookupEnvironment.unitBeingCompleted = unit;
            parser.getMethodBodies(unit);
            if (unit.scope != null) {
                unit.scope.faultInTypes();
                unit.scope.verifyMethods(lookupEnvironment.methodVerifier());
            }
            unit.resolve();
            unit.analyseCode();
            // This is where we differ from super: DON'T call generateCode().
            // Sadly we can't just set ignoreMethodBodies=true to have the same effect,
            // since that would also skip the analyseCode call, which we DO, want:
            //     unit.generateCode();
            if (options.produceReferenceInfo && unit.scope != null) {
                unit.scope.storeDependencyInfo();
            }
            unit.finalizeProblems();
            unit.compilationResult.totalUnitsKnown = totalUnits;
            lookupEnvironment.unitBeingCompleted = null;
        }
        @Override
        public void reset() {
            if (KEEP_LOOKUP_ENVIRONMENT) {
                // Same as super.reset() in ECJ 4.4.2, but omits the following statement:
                //  this.mLookupEnvironment.reset();
                // because we need the lookup environment to stick around even after the
                // parse phase is done: at that point we're going to use the parse trees
                // from java detectors which may need to resolve types
                this.parser.scanner.source = null;
                this.unitsToProcess = null;
                if (DebugRequestor != null) DebugRequestor.reset();
                this.problemReporter.reset();
            } else {
                super.reset();
            }
        }
    }

}
