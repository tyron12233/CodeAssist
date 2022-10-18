package com.tyron.completion.java.parse;

import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacScope;
import com.sun.tools.javac.api.JavacTaskImpl;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.comp.Attr;
import com.sun.tools.javac.comp.AttrContext;
import com.sun.tools.javac.comp.Enter;
import com.sun.tools.javac.comp.Env;
import com.sun.tools.javac.comp.Flow;
import com.sun.tools.javac.comp.TypeEnter;
import com.sun.tools.javac.parser.LazyDocCommentTable;
import com.sun.tools.javac.parser.Scanner;
import com.sun.tools.javac.parser.ScannerFactory;
import com.sun.tools.javac.tree.DocCommentTable;
import com.sun.tools.javac.tree.EndPosTable;
import com.sun.tools.javac.tree.JCTree;
import com.sun.tools.javac.tree.TreeInfo;
import com.sun.tools.javac.tree.TreeMaker;
import com.sun.tools.javac.util.Context;
import com.sun.tools.javac.util.JCDiagnostic;
import com.sun.tools.javac.util.Log;
import com.sun.tools.javac.util.Names;
import com.tyron.completion.java.compiler.services.CancelService;
import com.tyron.completion.java.compiler.services.NBLog;
import com.tyron.completion.java.compiler.services.NBParserFactory;

import java.io.IOException;
import java.nio.CharBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.tools.JavaFileObject;

public class DefaultPartialReparser implements PartialReparser {


    private static final Logger LOGGER = Logger.getLogger("PartialReparser");

    public DefaultPartialReparser() {

    }

    @Override
    public boolean reparseMethod(CompilationInfoImpl ci,
                                 CharSequence contents,
                                 CompilationUnitTree cu,
                                 MethodTree orig,
                                 String newBody) throws IOException {
        if (cu == null || newBody == null || orig.getBody() == null) {
            return false;
        }

        JavacTaskImpl task = ci.getJavacTask();
        Trees trees = Trees.instance(task);

        TreePath methodPath = trees.getPath(cu, orig);
        if (methodPath.getLeaf().getKind() != Tree.Kind.METHOD) {
            return false;
        }

        Scope methodScope = trees.getScope(methodPath);

        final int origStartPos =
                (int) trees.getSourcePositions().getStartPosition(cu, orig.getBody());
        final int origEndPos = (int) trees.getSourcePositions().getEndPosition(cu, orig.getBody());
        if (origStartPos < 0) {
            LOGGER.log(Level.WARNING, "Javac returned startpos: {0} < 0",
                    new Object[]{origStartPos});  //NOI18N
            return false;
        }
        if (origStartPos > origEndPos) {
            LOGGER.log(Level.WARNING, "Javac returned startpos: {0} > endpos: {1}",
                    new Object[]{origStartPos, origEndPos});  //NOI18N
            return false;
        }
        final FindAnonymousVisitor fav = new FindAnonymousVisitor();
        fav.scan(orig.getBody(), null);
        if (fav.hasLocalClass) {
            if (LOGGER.isLoggable(Level.FINER)) {
                LOGGER.log(Level.FINER, "Skip reparse method (old local classes): {0}",
                        newBody);   //NOI18N
            }
            return false;
        }
        final int noInner = fav.noInner;
        final Context ctx = task.getContext();
        try {
            final NBLog l = NBLog.instance(ctx);
            l.startPartialReparse(cu.getSourceFile());
            final JavaFileObject prevLogged = l.useSource(cu.getSourceFile());

            long start = System.currentTimeMillis();
            Map<JCTree, LazyDocCommentTable.Entry> docComments = new HashMap<>();
            JCTree.JCBlock block = reparseMethodBody(ctx, cu, orig, newBody + " ", docComments);
            EndPosTable endPos = ((JCTree.JCCompilationUnit) cu).endPositions;
            LOGGER.log(Level.FINER, "Reparsed method in: {0}", ci);     //NOI18N
            if (block == null) {
                LOGGER.log(Level.FINER, "Skip reparse method, invalid position, newBody: ",
                        //NOI18N
                        newBody);
                return false;
            }
            final int newEndPos = (int) trees.getSourcePositions().getEndPosition(cu, block);
            if (newEndPos != origStartPos + newBody.length()) {
                return false;
            }
            fav.reset();
            fav.scan(block, null);
            final int newNoInner = fav.noInner;
            if (fav.hasLocalClass || noInner != newNoInner) {
                if (LOGGER.isLoggable(Level.FINER)) {
                    LOGGER.log(Level.FINER, "Skeep reparse method (new local classes): {0}", ci);   //NOI18N
                }
                return false;
            }
            Map<JCTree, LazyDocCommentTable.Entry> docCommentsTable = ((LazyDocCommentTable) ((JCTree.JCCompilationUnit) cu).docComments).table;
            //noinspection SuspiciousMethodCalls
            docCommentsTable.keySet().removeAll(fav.docOwners);
            docCommentsTable.putAll(docComments);

            long end = System.currentTimeMillis();
            final int delta = newEndPos - origEndPos;
            final TranslatePositionsVisitor tpv = new TranslatePositionsVisitor(orig, endPos, delta);
            tpv.scan(cu, null);
            Enter.instance(ctx).unenter(((JCTree.JCCompilationUnit) cu), ((JCTree.JCMethodDecl) orig).body);
            ((JCTree.JCMethodDecl)orig).body = block;

            reattrMethodBody(ctx, methodScope, orig, block);
            if (LOGGER.isLoggable(Level.FINER)) {
                LOGGER.log(Level.FINER, "Resolved method in: {0}", ci);     //NOI18N
            }


        } catch (Throwable t) {
            if (t instanceof ThreadDeath) {
                throw (ThreadDeath) t;
            }
            t.printStackTrace();
            return false;
        }
        return true;
    }

    public JCTree.JCBlock reparseMethodBody(Context ctx,
                                            CompilationUnitTree topLevel,
                                            MethodTree methodToReparse,
                                            String newBodyText,
                                            final Map<JCTree, LazyDocCommentTable.Entry> docComments) throws IllegalArgumentException, IllegalAccessException {
        int startPos = ((JCTree.JCBlock) methodToReparse.getBody()).pos;
        char[] body = new char[startPos + newBodyText.length() + 1];
        Arrays.fill(body, 0, startPos, ' ');
        for (int i = 0; i < newBodyText.length(); i++) {
            body[startPos + i] = newBodyText.charAt(i);
        }
        body[startPos + newBodyText.length()] = '\u0000';
        CharBuffer buf = CharBuffer.wrap(body, 0, body.length - 1);
        com.sun.tools.javac.parser.JavacParser parser =
                newParser(ctx, buf, ((JCTree.JCBlock) methodToReparse.getBody()).pos,
                        ((JCTree.JCCompilationUnit) topLevel).endPositions);
        final JCTree.JCStatement statement = parser.parseStatement();
        if (statement.getKind() == Tree.Kind.BLOCK) {
            if (docComments != null) {
                DocCommentTable docCommentsTable = parser.getDocComments();
                docComments.putAll(((LazyDocCommentTable) docCommentsTable).table);
            }
            return (JCTree.JCBlock) statement;
        }
        return null;
    }

    public BlockTree reattrMethodBody(Context context,
                                      Scope scope,
                                      MethodTree methodToReparse,
                                      BlockTree block) throws IllegalArgumentException {
        Attr attr = Attr.instance(context);
//        assert ((JCTree.JCMethodDecl)methodToReparse).localEnv != null;
        JCTree.JCMethodDecl tree = (JCTree.JCMethodDecl) methodToReparse;
        final Names names = Names.instance(context);
        final Symtab syms = Symtab.instance(context);
        final TypeEnter typeEnter = TypeEnter.instance(context);
        final Log log = Log.instance(context);
        final TreeMaker make = TreeMaker.instance(context);
        final Env<AttrContext> env = ((JavacScope) scope).getEnv();//this is a copy anyway...
        final Symbol.ClassSymbol owner = env.enclClass.sym;
        if (tree.name == names.init && !owner.type.isErroneous() && owner.type != syms.objectType) {
            JCTree.JCBlock body = tree.body;
            if (body.stats.isEmpty() || !TreeInfo.isSelfCall(body.stats.head)) {
                body.stats = body.stats.prepend(make.at(body.pos)
                        .Exec(make.Apply(com.sun.tools.javac.util.List.nil(),
                                make.Ident(names._super), com.sun.tools.javac.util.List.nil())));
            } else if ((env.enclClass.sym.flags() & Flags.ENUM) != 0 &&
                       (tree.mods.flags & Flags.GENERATEDCONSTR) == 0 &&
                       TreeInfo.isSuperCall(body.stats.head)) {
                // enum constructors are not allowed to call super
                // directly, so make sure there aren't any super calls
                // in enum constructors, except in the compiler
                // generated one.
                log.error(tree.body.stats.head.pos(),
                        new JCDiagnostic.Error("compiler", "call.to.super.not.allowed.in.enum.ctor",
                                env.enclClass.sym));
            }
        }
        attr.attribStat((JCTree.JCBlock) block, env);
        return block;
    }

    public BlockTree reflowMethodBody(Context context,
                                      CompilationUnitTree topLevel,
                                      ClassTree ownerClass,
                                      MethodTree methodToReparse) {
        Flow flow = Flow.instance(context);
        TreeMaker make = TreeMaker.instance(context);
        Enter enter = Enter.instance(context);
        flow.analyzeTree(enter.getEnv(((JCTree.JCClassDecl) ownerClass).sym), make);
        return methodToReparse.getBody();
    }

    private com.sun.tools.javac.parser.JavacParser newParser(Context context,
                                                             CharSequence input,
                                                             int startPos,
                                                             final EndPosTable endPos) {
        NBParserFactory parserFactory =
                (NBParserFactory) NBParserFactory.instance(context); //TODO: eliminate the cast
        ScannerFactory scannerFactory = ScannerFactory.instance(context);
        CancelService cancelService = CancelService.instance(context);
        Scanner lexer = scannerFactory.newScanner(input, true);
//        lexer.seek(startPos);
        if (endPos instanceof NBParserFactory.NBJavacParser.EndPosTableImpl) {
            ((NBParserFactory.NBJavacParser.EndPosTableImpl) endPos).resetErrorEndPos();
        }
        return new NBParserFactory.NBJavacParser(parserFactory, lexer, true, false, true, false,
                cancelService) {
            @Override
            protected com.sun.tools.javac.parser.JavacParser.AbstractEndPosTable newEndPosTable(
                    boolean keepEndPositions) {
                return new com.sun.tools.javac.parser.JavacParser.AbstractEndPosTable(this) {

                    @Override
                    public void storeEnd(JCTree tree, int endpos) {
                        ((NBParserFactory.NBJavacParser.EndPosTableImpl) endPos).storeEnd(tree,
                                endpos);
                    }

                    @Override
                    protected <T extends JCTree> T to(T t) {
                        storeEnd(t, token.endPos);
                        return t;
                    }

                    @Override
                    protected <T extends JCTree> T toP(T t) {
                        storeEnd(t, S.prevToken().endPos);
                        return t;
                    }

                    @Override
                    public int getEndPos(JCTree tree) {
                        return endPos.getEndPos(tree);
                    }

                    @Override
                    public int replaceTree(JCTree oldtree, JCTree newtree) {
                        return endPos.replaceTree(oldtree, newtree);
                    }

                    @Override
                    public void setErrorEndPos(int errPos) {
                        super.setErrorEndPos(errPos);
                        ((NBParserFactory.NBJavacParser.EndPosTableImpl) endPos).setErrorEndPos(
                                errPos);
                    }
                };
            }
        };
    }
}
