package io.github.jaxsh.intramart;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * build 前的检查：平台用的是不是当前设定。没跑过 imSetup，或改了版本・模块构成・数据源等之后没重跑，就停下来提示，
 * 不悄悄用旧平台。
 */
@DisableCachingByDefault(because = "only compares settings")
public abstract class SetupCheckTask extends DefaultTask {

    @Internal
    public abstract Property<String> getFingerprint();

    @Internal
    public abstract RegularFileProperty getStamp();

    public SetupCheckTask() {
        getOutputs().upToDateWhen(t -> false);
    }

    @TaskAction
    public void check() throws IOException {
        final Path p = getStamp().get().getAsFile().toPath();
        if (!Files.isRegularFile(p)) {
            throw new GradleException("The intra-mart platform is not prepared yet: run ./gradlew imSetup");
        }
        final String want = getFingerprint().get();
        final String have = Files.readString(p, StandardCharsets.UTF_8);
        if (!want.equals(have)) {
            final StringBuilder diff = new StringBuilder();
            final String[] a = have.split("\n", -1);
            final String[] b = want.split("\n", -1);
            for (int i = 0; i < Math.max(a.length, b.length); i++) {
                final String x = i < a.length ? a[i] : "";
                final String y = i < b.length ? b[i] : "";
                if (!x.equals(y)) {
                    diff.append("\n  ").append(x).append("  ->  ").append(y);
                }
            }
            throw new GradleException("intra-mart settings changed since the platform was prepared: run ./gradlew imSetup" + diff);
        }
    }
}
