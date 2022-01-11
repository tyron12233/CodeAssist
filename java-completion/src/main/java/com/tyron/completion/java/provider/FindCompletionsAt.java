package com.tyron.completion.java.provider;

import org.openjdk.source.tree.CaseTree;
import org.openjdk.source.tree.CompilationUnitTree;
import org.openjdk.source.tree.ErroneousTree;
import org.openjdk.source.tree.IdentifierTree;
import org.openjdk.source.tree.ImportTree;
import org.openjdk.source.tree.LiteralTree;
import org.openjdk.source.tree.MemberReferenceTree;
import org.openjdk.source.tree.MemberSelectTree;
import org.openjdk.source.tree.SwitchTree;
import org.openjdk.source.tree.Tree;
import org.openjdk.source.util.JavacTask;
import org.openjdk.source.util.SourcePositions;
import org.openjdk.source.util.TreePath;
import org.openjdk.source.util.TreePathScanner;
import org.openjdk.source.util.Trees;

public class FindCompletionsAt extends TreePathScanner<TreePath, Long> {
    
    private static final String TAG = FindCompletionsAt.class.getSimpleName();

    private CompilationUnitTree root;
    private final SourcePositions pos;
    
	public FindCompletionsAt(JavacTask task) {
        pos = Trees.instance(task).getSourcePositions();
	}

    @Override
    public TreePath visitLiteral(LiteralTree t, Long find) {
        if (isInside(t, find)) {
            return getCurrentPath();
        }
        return super.visitLiteral(t, find);
    }

    @Override
    public TreePath visitCompilationUnit(CompilationUnitTree node, Long p) {
        root = node;
        return reduce(super.visitCompilationUnit(node, p), getCurrentPath());
    }

	@Override
	public TreePath visitIdentifier(IdentifierTree t, Long find) {
        if (isInside(t, find)) {
            return getCurrentPath();
        }
		return super.visitIdentifier(t, find);
	}

    @Override
    public TreePath visitImport(ImportTree t, Long find) {
        if (isInside(t, find)) {
            return getCurrentPath();
        }
        return super.visitImport(t, find);
    }
    
    @Override
    public TreePath visitMemberSelect(MemberSelectTree t, Long find) {
        if (isInside(t, find)) {
            return getCurrentPath();
        }
        return super.visitMemberSelect(t, find);
    }

    @Override
    public TreePath visitMemberReference(MemberReferenceTree t, Long find) {
        if (isInside(t, find)) {
            return getCurrentPath();
        }
        return super.visitMemberReference(t, find);
    }
    
    @Override
    public TreePath visitCase(CaseTree t, Long find) {
        if (isInside(t, find)) {
            return getCurrentPath();
        }
        return super.visitCase(t, find);
    }

    @Override
    public TreePath visitSwitch(SwitchTree t, Long find) {
        if (isInside(t, find)) {
            return getCurrentPath();
        }
        return super.visitSwitch(t, find);
    }

    @Override
    public TreePath visitErroneous(ErroneousTree node, Long find) {
        if (node.getErrorTrees() == null) {
            return null;
        }
        for (Tree tree : node.getErrorTrees()) {
            TreePath found = scan(tree, find);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean isInside(Tree t, long find) {
        long start = pos.getStartPosition(root, t);
        long end = pos.getEndPosition(root, t);
        if (start <= find && find <= end) {
            return true;
        }
        return false;
    }

	@Override
	public TreePath reduce(TreePath r1, TreePath r2) {
		if (r1 == null) {
			return r2;
		}
		return r1;
	}
    long start, end;
	public String test() {
        return "Identifier: " + start + ", " + end;
    }
}
