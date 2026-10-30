package io.github.jaxsh.intramart;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * imSetup 的一步：编译・测试用的 intra-mart API。
 * <ul>
 * <li>libraries=platform：把平台 WEB-INF/lib 的 jar 放进共享缓存，写下路径；</li>
 * <li>libraries=maven（缺省）：用官方 Maven 仓库，只反查与解决结果一致的 BOM 版本写下来，依赖由用户在 dependencies 里不带版本地声明。</li>
 * </ul>
 */
@DisableCachingByDefault(because = "reads and writes shared caches outside the build")
public abstract class ImLibrariesTask extends DefaultTask {

    @Internal
    public abstract DirectoryProperty getRepository();

    /** 共享 jar 缓存（缺省 ~/.gradle/caches/intra-mart/jars）。 */
    @Internal
    public abstract DirectoryProperty getCache();

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    public abstract DirectoryProperty getJugglingProject();

    @Input
    public abstract Property<String> getRuntime();

    @Input
    public abstract Property<Boolean> getSamples();

    /** 一行一个 jar 的绝对路径（maven 模式为空）。 */
    @OutputFile
    public abstract RegularFileProperty getClasspathFile();

    /** platform・maven。 */
    @Input
    public abstract Property<String> getMode();

    /** maven 模式：选中的 BOM 版本（只作记录；platform 模式为空）。 */
    @OutputFile
    public abstract RegularFileProperty getBomFile();

    @Input
    public abstract Property<String> getBaseVersion();

    @Input
    public abstract Property<String> getMavenRepositoryUrl();

    /** BOM 的 pom 缓存。 */
    @Internal
    public abstract DirectoryProperty getBomCache();

    public ImLibrariesTask() {
        // 每次都核一遍（只读各模块的 module.xml 与缓存清单，不到 1 秒）：缓存被删了也能补回来
        getOutputs().upToDateWhen(t -> false);
    }

    @TaskAction
    public void run() throws IOException {
        final Path out = getClasspathFile().get().getAsFile().toPath();
        final Path bomOut = getBomFile().get().getAsFile().toPath();
        Files.createDirectories(out.getParent());
        if ("maven".equals(getMode().get())) {
            final Path im = getJugglingProject().get().getAsFile().toPath().resolve("juggling.im");
            final MavenBom maven = new MavenBom(getBomCache().get().getAsFile().toPath(), getMavenRepositoryUrl().get());
            final String bom = maven.forBase(getBaseVersion().get());
            final List<String> diff = maven.differences(im, bom);
            if (!diff.isEmpty()) {
                getLogger().warn("{} modules differ from im_bom:{} (compile against the BOM version, run with the platform's): {}{}",
                        diff.size(), bom, String.join(", ", diff.subList(0, Math.min(5, diff.size()))), diff.size() > 5 ? ", ..." : "");
            }
            Files.writeString(bomOut, bom + "\n", StandardCharsets.UTF_8);
            Files.writeString(out, "", StandardCharsets.UTF_8);
            getLogger().lifecycle("intra-mart libraries: Maven BOM jp.co.intra_mart:im_bom:{} ({})", bom, getMavenRepositoryUrl().get());
            return;
        }
        Files.writeString(bomOut, "", StandardCharsets.UTF_8);
        final Path cache = getCache().get().getAsFile().toPath();
        final List<Path> jars = LibraryCache.resolve(getRepository().get().getAsFile().toPath(),
                getJugglingProject().get().getAsFile().toPath(), cache, getRuntime().get(), getSamples().get());
        Files.writeString(out, jars.stream().map(p -> p.toAbsolutePath().toString()).collect(Collectors.joining("\n", "", "\n")),
                StandardCharsets.UTF_8);
        getLogger().lifecycle("intra-mart libraries: {} jars (cache {})", jars.size(), cache);
    }
}
