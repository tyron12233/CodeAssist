package com.tyron.completion.java.concurrent;

import static com.tyron.completion.TestUtil.resolveBasePath;

import androidx.test.core.app.ApplicationProvider;

import com.tyron.builder.project.Project;
import com.tyron.builder.project.mock.MockAndroidModule;
import com.tyron.builder.project.mock.MockFileManager;
import com.tyron.completion.TestUtil;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.CompilerContainer;
import com.tyron.completion.java.CompletionModule;
import com.tyron.completion.java.JavaCompilerProvider;
import com.tyron.completion.java.JavaCompilerService;
import com.tyron.completion.java.JavaCompletionProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * The java language server is single threaded and requires threads that
 * accesses the CompileBatch to be synchronized, this test ensured that threads
 * are properly synchronized and doesn't throw a RuntimeException with Compiler still in use error.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, resourceDir = Config.NONE)
public class MultiThreadTest {

    private File mRoot;
    private MockFileManager mFileManager;
    private Project mProject;
    private MockAndroidModule mModule;
    private JavaCompilerService mService;

    @Before
    public void setup() throws IOException {
        CompletionModule.initialize(ApplicationProvider.getApplicationContext());
        CompletionModule.setAndroidJar(new File(resolveBasePath(), "classpath/rt.jar"));
        CompletionModule.setLambdaStubs(new File(resolveBasePath(),
                "classpath/core-lambda-stubs" + ".jar"));

        JavaCompilerProvider provider = new JavaCompilerProvider();
        CompilerService.getInstance().registerIndexProvider(JavaCompilerProvider.KEY, provider);

        mRoot = new File(TestUtil.resolveBasePath(), "EmptyProject");
        mFileManager = new MockFileManager(mRoot);
        mProject = new Project(mRoot);
        mModule = new MockAndroidModule(mRoot, mFileManager);
        mModule.open();

        File[] testFiles = new File(mRoot, "completion").listFiles(c -> c.getName().endsWith(
                ".java"));
        if (testFiles != null) {
            for (File testFile : testFiles) {
                mModule.addJavaFile(testFile);
            }
        }

        mService = provider.get(mProject, mModule);
    }

    @Test
    public void testMultiThreaded() throws InterruptedException {
        File file = mModule.getJavaFile("com.tyron.test.MemberSelect");
        assert file != null;

        List<Thread> threads = new ArrayList<>();

        Thread thread = new Thread(() -> {
            CompilerContainer container = mService.compile(file.toPath());
            container.run(task -> {
                System.out.println(Thread.currentThread().getName());
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            });
        }, "Slow task");
        threads.add(thread);
        thread.start();

        for (int i = 0; i < 300; i++) {
            int finalI = i;
            Thread t = new Thread(() -> {
                CompilerContainer container = mService.compile(file.toPath());
                container.run((task -> {
                    System.out.println(Thread.currentThread().getName());
                }));
            }, "Thread " + i);
            threads.add(t);
            t.start();
        }

        threads.forEach(it -> {
            try {
                it.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        });
    }

    private void w(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
    }
}
