package com.tyron.builder.initialization;

import org.gradle.internal.installation.CurrentGradleInstallation;
import org.gradle.internal.installation.GradleInstallation;
import org.gradle.internal.jvm.Jvm;
import org.junit.Test;

import java.io.File;

public class InitializationTest {

    @Test
    public void testInitialization() {
        Jvm current = Jvm.current();
        CurrentGradleInstallation.setInstance(
                new CurrentGradleInstallation(
                        new GradleInstallation(
                                new File("C:\\Users\\TyronScott\\" +
                                         ".gradle\\wrapper\\dists\\gradle-7.4-bin\\c0gwcg53nkjbqw7r0h0umtfvt\\gradle-7.4")
                        )
                )
        );
        Launcher.main(new String[0]);
    }
}
