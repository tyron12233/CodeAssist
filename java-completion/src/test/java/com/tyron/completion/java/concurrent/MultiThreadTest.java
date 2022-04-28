package com.tyron.completion.java.concurrent;

import static com.tyron.completion.TestUtil.resolveBasePath;

import androidx.test.core.app.ApplicationProvider;

import com.tyron.builder.model.SourceFileObject;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.mock.MockAndroidModule;
import com.tyron.builder.project.mock.MockFileManager;
import com.tyron.completion.TestUtil;
import com.tyron.completion.index.CompilerService;
import com.tyron.completion.java.compiler.CompilerContainer;
import com.tyron.completion.java.CompletionModule;
import com.tyron.completion.java.JavaCompilerProvider;
import com.tyron.completion.java.compiler.JavaCompilerService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

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
    public void testMultipleReaders() throws InterruptedException {
        File file = mModule.getJavaFile("com.tyron.test.MemberSelect");
        assert file != null;

        // compile for the first time
        CompilerContainer container = mService.compile(file.toPath());

        List<Thread> readers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            readers.add(new Thread(() -> {
                container.run(task -> {
                    w(100);

                    System.out.println(Thread.currentThread());
                });
            }, "Reader #" + i));
        }

        // simulate file change
        SourceFileObject sourceFileObject = new SourceFileObject(file.toPath(), mModule, Instant.now());
        CompilerContainer compile =
                mService.compile(Collections.singletonList(sourceFileObject));

        for (Thread reader : readers) {
            reader.start();
        }

        new Thread(() -> {
            compile.run(task -> {
                System.out.println("Writer thread.");
            });
        }).start();

        for (Thread reader : readers) {
            reader.join();
        }
    }

    @Test
    public void testClosedFileChannel() {
        File file = mModule.getJavaFile("com.tyron.test.MemberSelect");
        assert file != null;

        new Thread(() -> {
            CompilerContainer compile = mService.compile(file.toPath());
            compile.run(task -> {
                w(500);
            });
        }).start();

        new Thread(() -> {
            CompilerContainer compile = mService.compile(file.toPath());
            compile.run(compileTask -> {
               w(200);
            });
        }).start();

        CompilerContainer compile = mService.compile(file.toPath());
        compile.run(task -> {
           assert task.diagnostics.isEmpty() : task.diagnostics;
        });
    }

    @Test
    public void testMultiThreaded() {
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
