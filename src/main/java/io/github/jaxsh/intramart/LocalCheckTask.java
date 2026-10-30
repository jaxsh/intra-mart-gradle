package io.github.jaxsh.intramart;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * 本机部署（imStart・imRun・imRestart・imDebug）的前置检查：本机设定（Resin 的位置、数据源、JDK）缺了或不对，
 * 在准备平台・拼 WAR 目录之前就停下，一次列出缺的项。不然要等到 imResinConfig 才报错。
 */
@DisableCachingByDefault(because = "only checks settings")
public abstract class LocalCheckTask extends DefaultTask {

    @Internal
    public abstract DirectoryProperty getResinHome();

    @Internal
    public abstract Property<String> getDatasourceUrl();

    @Internal
    public abstract Property<String> getJavaHome();

    /** storage（租户的业务数据）：只报位置；还没有时提醒（第一次テナント環境セットアップ时才建）。 */
    @Internal
    public abstract DirectoryProperty getStorageDir();

    public LocalCheckTask() {
        getOutputs().upToDateWhen(t -> false);
    }

    @TaskAction
    public void check() {
        final List<String> missing = new ArrayList<>();
        final List<String> wrong = new ArrayList<>();
        if (!getResinHome().isPresent()) {
            missing.add("intraMart.resin.home");
        } else {
            final File home = getResinHome().get().getAsFile();
            if (!new File(home, "lib/resin.jar").isFile()) {
                wrong.add("intraMart.resin.home: no lib/resin.jar in " + home);
            }
        }
        if (!getDatasourceUrl().isPresent() || getDatasourceUrl().get().isBlank()) {
            missing.add("intraMart.datasource.url");
        }
        final String java = getJavaHome().getOrElse("");
        if (!new File(java, "bin/java.exe").isFile() && !new File(java, "bin/java").isFile()) {
            wrong.add("intraMart.resin.javaHome: no bin/java in " + java);
        }
        if (missing.isEmpty() && wrong.isEmpty()) {
            final File storage = getStorageDir().get().getAsFile();
            if (storage.isDirectory()) {
                getLogger().lifecycle("Storage: {}", storage);
            } else {
                getLogger().warn("Storage {} does not exist yet; it is created by the first tenant setup. "
                        + "If this database was already set up, set intraMart.storageDir to the storage that belongs to it.", storage);
            }
            return;
        }
        final List<String> problems = new ArrayList<>();
        missing.forEach(k -> problems.add(k + " is not set"));
        problems.addAll(wrong);
        throw new GradleException(String.join("\n", problems));
    }
}
