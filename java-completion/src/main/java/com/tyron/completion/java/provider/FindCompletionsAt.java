package com.tyron.completion.java.provider;

import com.sun.source.tree.CaseTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ErroneousTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberReferenceTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.SwitchTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;

public class FindCompletionsAt extends TreePathScanner<TreePath, Long> {
    
    private static final String TAG = FindCompletionsAt.class.getSimpleName();
    
	private final JavacTask task;
	private CompilationUnitTree root;
    private SourcePositions pos;
    
	public FindCompletionsAt(JavacTask task) {
		this.task = task;
        pos = Trees.instance(task).getSourcePositions();
	}

    @Override
    public TreePath visitCompilationUnit(CompilationUnitTree node, Long p) {
        root = node;
        return reduce(super.visitCompilationUnit(node, p), getCurrentPath());
    }
    

	@Override
	public TreePath visitIdentifier(IdentifierTree node, Long find) {
        long start = pos.getStartPosition(root, node);
        long end = pos.getEndPosition(root, node);
        if (start <= find && find <= end) {
            return getCurrentPath();
        }
		return super.visitIdentifier(node, find);
	}

    @Override
    public TreePath visitImport(ImportTree node, Long find) {
        long start = pos.getStartPosition(root, node);
        long end = pos.getEndPosition(root, node);
        if (start <= find && find <= end) {
            return getCurrentPath();
        }
        return super.visitImport(node, find);
    }
    
    @Override
    public TreePath visitMemberSelect(MemberSelectTree node, Long find) {
        long start = pos.getEndPosition(root, node.getExpression()) + 1;
        long end = pos.getEndPosition(root, node);
        if (start <= find && find <= end) {
            return getCurrentPath();
        }
        return super.visitMemberSelect(node, find);
    }

    @Override
    public TreePath visitMemberReference(MemberReferenceTree t, Long find) {
        long start = pos.getEndPosition(root, t.getQualifierExpression()) + 2;
        long end = pos.getEndPosition(root, t);
        if (start <= find && find <= end) {
            return getCurrentPath();
        }
        return super.visitMemberReference(t, find);
    }
    
    @Override
    public TreePath visitCase(CaseTree node, Long find) {
        long start = pos.getStartPosition(root, node);
        long end = pos.getEndPosition(root, node);
        if (start <= find && find <= end) {
            return getCurrentPath();
        }
        return super.visitCase(node, find);
    }

    @Override
    public TreePath visitSwitch(SwitchTree t, Long find) {
        long start = pos.getStartPosition(root, t);
        long end = pos.getEndPosition(root, t);
        if (start <= find && find <= end) {
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
