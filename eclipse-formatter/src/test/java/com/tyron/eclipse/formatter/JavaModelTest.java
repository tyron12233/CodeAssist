package com.tyron.eclipse.formatter;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.eclipse.core.internal.runtime.Activator;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.IWorkspaceRoot;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IImportDeclaration;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IPackageDeclaration;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.IProblemRequestor;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.WorkingCopyOwner;
import org.eclipse.jdt.core.compiler.IProblem;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.BundleContext;
import org.osgi.framework.launch.Framework;
import org.osgi.framework.launch.FrameworkFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.ServiceLoader;

@RunWith(BundleClassLoader.class)
public class JavaModelTest {

    // used java project
    protected IJavaProject currentProject;

    protected ICompilationUnit[] workingCopies;
    protected WorkingCopyOwner wcOwner;

    @Test
    public void testCompletion() throws Exception {
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());

        FrameworkFactory next = ServiceLoader.load(FrameworkFactory.class).iterator().next();
        assert next != null;

        Map<String, String> config = new HashMap<>();
        config.put("osgi.console", "");
        config.put("osgi.clean", "true");
        config.put("osgi.noShutdown", "true");
        config.put("eclipse.ignoreApp", "true");
        config.put("osgi.bundles.defaultStartLevel", "4");
        config.put("osgi.configuration.area", "./configuration");

        // automated bundles deployment
        config.put("felix.fileinstall.dir", "./dropins");
        config.put("felix.fileinstall.noInitialDelay", "true");
        config.put("felix.fileinstall.start.level", "4");

        Framework framework = next.newFramework(config);
        framework.start();

        BundleContext context = framework.getBundleContext();
        ((BundleClassLoader.TestClassLoader) getClass().getClassLoader()).setBundle(context.getBundle());
        new Activator().start(context);
    }

    public ICompilationUnit getWorkingCopy(String path, boolean computeProblems) throws JavaModelException {
        return getWorkingCopy(path, "", computeProblems);
    }

    public ICompilationUnit getWorkingCopy(String path, String source) throws JavaModelException {
        return getWorkingCopy(path, source, false);
    }

    public ICompilationUnit getWorkingCopy(String path, String source, boolean computeProblems) throws JavaModelException {
        if (this.wcOwner == null) {
            this.wcOwner = newWorkingCopyOwner(computeProblems ? new BasicProblemRequestor() : null);
            return getWorkingCopy(path, source, this.wcOwner);
        }
        ICompilationUnit wc = getWorkingCopy(path, source, this.wcOwner);
        // Verify that compute problem parameter is compatible with working copy problem requestor
        if (computeProblems) {
            assertNotNull("Cannot compute problems if the problem requestor of the working copy owner is set to null!", this.wcOwner.getProblemRequestor(wc));
        } else {
            assertNull("Cannot ignore problems if the problem requestor of the working copy owner is not set to null!", this.wcOwner.getProblemRequestor(wc));
        }
        return wc;
    }

    /**
     * Create a new working copy owner using given problem requestor
     * to report problem.
     *
     * @param problemRequestor The requestor used to report problems
     * @return The created working copy owner
     */
    protected WorkingCopyOwner newWorkingCopyOwner(final IProblemRequestor problemRequestor) {
        return new WorkingCopyOwner() {
            public IProblemRequestor getProblemRequestor(ICompilationUnit unit) {
                return problemRequestor;
            }
        };
    }

    public ICompilationUnit getWorkingCopy(String path, String source, WorkingCopyOwner owner) throws JavaModelException {
        ICompilationUnit workingCopy = getCompilationUnit(path);
        if (owner != null)
            workingCopy = workingCopy.getWorkingCopy(owner, null/*no progress monitor*/);
        else
            workingCopy.becomeWorkingCopy(null/*no progress monitor*/);
        workingCopy.getBuffer().setContents(source);
        if (owner != null) {
            IProblemRequestor problemRequestor = owner.getProblemRequestor(workingCopy);
            if (problemRequestor instanceof ProblemRequestor) {
                ((ProblemRequestor) problemRequestor).initialize(source.toCharArray());
            }
        }
        workingCopy.makeConsistent(null/*no progress monitor*/);
        return workingCopy;
    }

    /**
     * This method is still necessary when we need to use an owner and a specific problem requestor
     * (typically while using primary owner).
     * @deprecated
     */
    public ICompilationUnit getWorkingCopy(String path, String source, WorkingCopyOwner owner, IProblemRequestor problemRequestor) throws JavaModelException {
        ICompilationUnit workingCopy = getCompilationUnit(path);
        if (owner != null)
            workingCopy = workingCopy.getWorkingCopy(owner, problemRequestor, null/*no progress monitor*/);
        else
            workingCopy.becomeWorkingCopy(problemRequestor, null/*no progress monitor*/);
        workingCopy.getBuffer().setContents(source);
        if (problemRequestor instanceof ProblemRequestor)
            ((ProblemRequestor) problemRequestor).initialize(source.toCharArray());
        workingCopy.makeConsistent(null/*no progress monitor*/);
        return workingCopy;
    }

    protected ICompilationUnit getCompilationUnit(String path) {
        return (ICompilationUnit) JavaCore.create(getFile(path));
    }
    /**
     * Returns the specified compilation unit in the given project, root, and
     * package fragment or <code>null</code> if it does not exist.
     */
    public ICompilationUnit getCompilationUnit(String projectName, String rootPath, String packageName, String cuName) throws JavaModelException {
        IPackageFragment pkg= getPackageFragment(projectName, rootPath, packageName);
        if (pkg == null) {
            return null;
        }
        return pkg.getCompilationUnit(cuName);
    }

    /**
     * Returns the specified compilation unit in the given project, root, and
     * package fragment or <code>null</code> if it does not exist.
     */
    public ICompilationUnit[] getCompilationUnits(String projectName, String rootPath, String packageName) throws JavaModelException {
        IPackageFragment pkg= getPackageFragment(projectName, rootPath, packageName);
        if (pkg == null) {
            return null;
        }
        return pkg.getCompilationUnits();
    }

    /**
     * Returns the specified package fragment in the given project and root, or
     * <code>null</code> if it does not exist.
     * The rootPath must be specified as a project relative path. The empty
     * path refers to the default package fragment.
     */
    public IPackageFragment getPackageFragment(String projectName, String rootPath, String packageName) throws JavaModelException {
        IPackageFragmentRoot root= getPackageFragmentRoot(projectName, rootPath);
        if (root == null) {
            return null;
        }
        return root.getPackageFragment(packageName);
    }

    /**
     * Returns the specified package fragment root in the given project, or
     * <code>null</code> if it does not exist.
     * If relative, the rootPath must be specified as a project relative path.
     * The empty path refers to the package fragment root that is the project
     * folder itself.
     * If absolute, the rootPath refers to either an external jar, or a resource
     * internal to the workspace
     */
    public IPackageFragmentRoot getPackageFragmentRoot(
            String projectName,
            String rootPath)
            throws JavaModelException {

        IJavaProject project = getJavaProject(projectName);
        if (project == null) {
            return null;
        }
        IPath path = new Path(rootPath);
        if (path.isAbsolute()) {
            IWorkspaceRoot workspaceRoot = ResourcesPlugin.getWorkspace().getRoot();
            IResource resource = workspaceRoot.findMember(path);
            IPackageFragmentRoot root;
            if (resource == null) {
                // external jar
                root = project.getPackageFragmentRoot(rootPath);
            } else {
                // resource in the workspace
                root = project.getPackageFragmentRoot(resource);
            }
            return root;
        } else {
            IPackageFragmentRoot[] roots = project.getPackageFragmentRoots();
            if (roots == null || roots.length == 0) {
                return null;
            }
            for (int i = 0; i < roots.length; i++) {
                IPackageFragmentRoot root = roots[i];
                if (!root.isExternal()
                    && root.getUnderlyingResource().getProjectRelativePath().equals(path)) {
                    return root;
                }
            }
        }
        return null;
    }

    protected ICompilationUnit getCompilationUnitFor(IJavaElement element) {

        if (element instanceof ICompilationUnit) {
            return (ICompilationUnit) element;
        }

        if (element instanceof IMember) {
            return ((IMember) element).getCompilationUnit();
        }

        if (element instanceof IPackageDeclaration || element instanceof IImportDeclaration) {
            return (ICompilationUnit) element.getParent();
        }

        return null;
    }

    protected IFile getFile(String path) {
        return getWorkspaceRoot().getFile(new Path(path));
    }

    /**
     * Returns the Java Project with the given name in this test
     * suite's model. This is a convenience method.
     */
    public IJavaProject getJavaProject(String name) {
        IProject project = getProject(name);
        return JavaCore.create(project);
    }

    protected IProject getProject(String project) {
        return getWorkspaceRoot().getProject(project);
    }

    /**
     * Returns the IWorkspace this test suite is running on.
     */
    public IWorkspace getWorkspace() {
        return ResourcesPlugin.getWorkspace();
    }
    public IWorkspaceRoot getWorkspaceRoot() {
        return getWorkspace().getRoot();
    }

    public static class BasicProblemRequestor implements IProblemRequestor {
        public void acceptProblem(IProblem problem) {}
        public void beginReporting() {}
        public void endReporting() {}
        public boolean isActive() {
            return true;
        }
    }

    public static class ProblemRequestor implements IProblemRequestor {
        public StringBuffer problems;
        public int problemCount;
        protected char[] unitSource;
        public boolean isActive = true;
        public ProblemRequestor() {
            initialize(null);
        }
        public void acceptProblem(IProblem problem) {
            //appendProblem(this.problems, problem, this.unitSource, ++this.problemCount);
            this.problems.append("----------\n");
        }
        public void beginReporting() {
            this.problems.append("----------\n");
        }
        public void endReporting() {
            if (this.problemCount == 0)
                this.problems.append("----------\n");
        }
        public boolean isActive() {
            return this.isActive;
        }
        public void initialize(char[] source) {
            reset();
            this.unitSource = source;
        }
        public void reset() {
            this.problems = new StringBuffer();
            this.problemCount = 0;
        }
    }
}
