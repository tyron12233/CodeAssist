package com.tyron.completion.java.compiler;

import android.annotation.SuppressLint;
import android.util.Log;

import com.tyron.builder.model.SourceFileObject;
import com.tyron.builder.project.Project;

import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.tools.Diagnostic;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ErroneousTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.api.JavacTool;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

public class Parser {

    private static final JavaCompiler COMPILER = JavacTool.create();
    private static SourceFileManager FILE_MANAGER;

    /**
     * Create a task that compiles a single file
     */
    @SuppressLint("NewApi")
    private static JavacTask singleFileTask(Project project, JavaFileObject file) {
        return (JavacTask)
                COMPILER.getTask(null, getFileManager(project), Parser::ignoreError, Collections.emptyList(), Collections.emptyList(), Collections.singletonList(file));
    }

    private static SourceFileManager getFileManager(Project project) {
        return new SourceFileManager(project);
    }


    public final JavaFileObject file;
    public final String contents;
    public final JavacTask task;
    public final CompilationUnitTree root;
    public final Trees trees;

    private Parser(Project project, JavaFileObject file) {
        this.file = file;
        try {
            this.contents = file.getCharContent(false).toString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.task = singleFileTask(project, file);
        try {
            this.root = task.parse().iterator().next();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        this.trees = Trees.instance(task);
    }

    public static Parser parseFile(Project project, Path file) {
        return parseJavaFileObject(project, new SourceFileObject(file));
    }

    private static Parser cachedParse;
    private static long cachedModified = -1;

    private static boolean needsParse(JavaFileObject file) {
        if (cachedParse == null) return true;
        if (!cachedParse.file.equals(file)) return true;
        if (file.getLastModified() > cachedModified) return true;
        return false;
    }

    private static void loadParse(Project project, JavaFileObject file) {
        cachedParse = new Parser(project, file);
        cachedModified = file.getLastModified();
    }

    public static Parser parseJavaFileObject(Project project, JavaFileObject file) {
        if (needsParse(file)) {
            loadParse(project, file);
        }
        return cachedParse;
    }

    public Set<Name> packagePrivateClasses() {
        Set<Name> result = new HashSet<>();
        for (Tree t : root.getTypeDecls()) {
            if (t instanceof ClassTree) {
                ClassTree c = (ClassTree) t;
                boolean isPublic = c.getModifiers().getFlags().contains(Modifier.PUBLIC);
                if (isPublic) {
                    result.add(c.getSimpleName());
                }
            }
        }
        return result;
    }

    private static String prune(
            final CompilationUnitTree root,
            final SourcePositions pos,
            final StringBuilder buffer,
            final long[] offsets,
            final boolean eraseAfterCursor) {
        class Scan extends TreeScanner<Void, Void> {
            boolean erasedAfterCursor = !eraseAfterCursor;

            boolean containsCursor(Tree node) {
                long start = pos.getStartPosition(root, node);
                long end = pos.getEndPosition(root, node);
                for (long cursor : offsets) {
                    if (start <= cursor && cursor <= end) {
                        return true;
                    }
                }
                return false;
            }

            boolean anyContainsCursor(Collection<? extends Tree> nodes) {
                for (Tree n : nodes) {
                    if (containsCursor(n)) return true;
                }
                return false;
            }

            long lastCursorIn(Tree node) {
                long start = pos.getStartPosition(root, node);
                long end = pos.getEndPosition(root, node);
                long last = -1;
                for (long cursor : offsets) {
                    if (start <= cursor && cursor <= end) {
                        last = cursor;
                    }
                }
                if (last == -1) {
                    throw new RuntimeException(
                            String.format("No cursor in %s is between %d and %d", offsets, start, end));
                }
                return last;
            }

            @Override
            public Void visitImport(ImportTree node, Void __) {
                // Erase 'static' keyword so autocomplete works better
                if (containsCursor(node) && node.isStatic()) {
                    int start = (int) pos.getStartPosition(root, node);
                    start = buffer.indexOf("static", start);
                    int end = start + "static".length();
                    erase(buffer, start, end);
                }
                return super.visitImport(node, null);
            }

            @Override
            public Void visitSwitch(SwitchTree node, Void __) {
                if (containsCursor(node)) {
                    // Prevent the enclosing block from erasing the closing } of the switch
                    erasedAfterCursor = true;
                }
                return super.visitSwitch(node, null);
            }

            @Override
            public Void visitBlock(BlockTree node, Void __) {
                if (containsCursor(node)) {
                    super.visitBlock(node, null);
                    // When we find the deepest block that includes the cursor
                    if (!erasedAfterCursor) {
                        long cursor = lastCursorIn(node);
                        long start = cursor;
                        long end = pos.getEndPosition(root, node);
                        if (end >= buffer.length()) end = buffer.length() - 1;
                        // Find the next line
                        while (start < end && buffer.charAt((int) start) != '\n') start++;
                        // Find the end of the block
                        while (end > start && buffer.charAt((int) end) != '}') end--;
                        // Erase from next line to end of block
                        erase(buffer, start, end - 1);
                        erasedAfterCursor = true;
                    }
                } else if (!node.getStatements().isEmpty()) {
                    StatementTree first = node.getStatements().get(0);
                    StatementTree last = node.getStatements().get(node.getStatements().size() - 1);
                    long start = pos.getStartPosition(root, first);
                    long end = pos.getEndPosition(root, last);
                    if (end >= buffer.length()) end = buffer.length() - 1;
                    erase(buffer, start, end);
                }
                return null;
            }

            @Override
            public Void visitErroneous(ErroneousTree node, Void nothing) {
                return super.scan(node.getErrorTrees(), nothing);
            }
        }

        new Scan().scan(root, null);

        String pruned = buffer.toString();
        // For debugging:
        if (false) {
            Path file = Paths.get(root.getSourceFile().toUri());
            Path out = file.resolveSibling(file.getFileName() + ".pruned");
            /*try {
              //  Files.writeString(out, pruned);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }*/
        }
        return pruned;
    }

    private static void erase(StringBuilder buffer, long start, long end) {
        for (int i = (int) start; i < end; i++) {
            switch (buffer.charAt(i)) {
                case '\r':
                case '\n':
                    break;
                default:
                    buffer.setCharAt(i, ' ');
            }
        }
    }

    public String prune(long cursor) {
        SourcePositions pos = Trees.instance(task).getSourcePositions();
        StringBuilder buffer = new StringBuilder(contents);
        long[] cursors = {cursor};
        return prune(root, pos, buffer, cursors, true);
    }

    private static void ignoreError(Diagnostic<? extends JavaFileObject> __) {
        // Too noisy, this only comes up in parse tasks which tend to be less important
        // LOG.warning(err.getMessage(Locale.getDefault()));
    }
}
