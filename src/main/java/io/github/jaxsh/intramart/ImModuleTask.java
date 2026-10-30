package io.github.jaxsh.intramart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * 把本工程打成 intra-mart 的ユーザモジュール（.imm）：IM-Juggling「ユーザモジュール」标签页能导入，官方 Docker Stacks 的
 * additional-modules 与 Accel CLI 的 deploy 也收。格式同官方模块的 imm（juggling-core dist/model/artifact-types.js）：
 * zip 根下 module.xml，jar 为 {@code <artifactId>-<version>-main.jar}，其余按 {@link ModuleLayout} 每个目录一个
 * {@code <artifactId>-<version>-main-<种别>.zip}（目录不存在或为空就不出）。
 * module.xml 的写法照官方模块项目模板（accelplatform/skills，Apache-2.0）：id・version・type=module・name・vendor・description。
 */
@DisableCachingByDefault(because = "cheap zip of project outputs")
public abstract class ImModuleTask extends DefaultTask {

    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+(\\.\\d+)?(-[A-Za-z0-9_.]+)?");

    /** 固定条目时刻（1980-01-01，zip 能表示的最早值）：同样的输入出同样的字节。 */
    private static final long FIXED_TIME = 315532800000L;

    /** 模块 ID（缺省 group.name）。 */
    @Input
    public abstract Property<String> getModuleId();

    /** 构件名前缀（缺省工程名）。 */
    @Input
    public abstract Property<String> getArtifactId();

    /** 模块版本 major.minor[.micro][-qualifier]（缺省工程 version）。 */
    @Input
    public abstract Property<String> getVersion();

    @Input
    public abstract Property<String> getModuleName();

    @Input
    public abstract Property<String> getVendor();

    @Input
    public abstract Property<String> getModuleDescription();

    /** 本工程的 jar（java・resources）。 */
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getJar();

    /** src/main：下面按 {@link ModuleLayout#DIRS} 取各目录。 */
    @Internal
    public abstract DirectoryProperty getSourceRoot();

    @OutputFile
    public abstract RegularFileProperty getOutput();

    public ImModuleTask() {
        // 源目录的内容变化靠 Gradle 的输入指纹不好表达（目录可有可无）：每次都重打，几十毫秒
        getOutputs().upToDateWhen(t -> false);
    }

    @TaskAction
    public void pack() throws IOException {
        final String version = getVersion().get();
        if (!VERSION.matcher(version).matches()) {
            throw new GradleException("Module version must look like 1.0.0 or 1.0.0-SNAPSHOT (set the project version): " + version);
        }
        final String prefix = getArtifactId().get() + "-" + version + "-main";
        final Path out = getOutput().get().getAsFile().toPath();
        Files.createDirectories(out.getParent());
        final Path root = getSourceRoot().get().getAsFile().toPath();
        int artifacts = 0;
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out), StandardCharsets.UTF_8)) {
            put(zip, "module.xml", moduleXml(version).getBytes(StandardCharsets.UTF_8));
            put(zip, prefix + ".jar", Files.readAllBytes(getJar().get().getAsFile().toPath()));
            for (final ModuleLayout.Dir d : ModuleLayout.DIRS) {
                final Path dir = root.resolve(d.source());
                if (!Files.isDirectory(dir)) {
                    continue;
                }
                final List<String> files = WebappTask.listFiles(dir);
                if (files.isEmpty()) {
                    continue;
                }
                final ByteArrayOutputStream inner = new ByteArrayOutputStream();
                try (ZipOutputStream z = new ZipOutputStream(inner, StandardCharsets.UTF_8)) {
                    for (final String rel : files) {
                        put(z, rel, Files.readAllBytes(dir.resolve(rel)));
                    }
                }
                put(zip, prefix + "-" + d.kind() + ".zip", inner.toByteArray());
                artifacts++;
            }
        }
        getLogger().lifecycle("User module {} {} -> {} (jar + {} artifact zips)", getModuleId().get(), version, out, artifacts);
    }

    private String moduleXml(final String version) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <module xmlns="urn:intramart:jackling:module">
                  <id>%s</id>
                  <version>%s</version>
                  <type>module</type>
                  <name>%s</name>
                  <vendor>%s</vendor>
                  <description>%s</description>
                </module>
                """.formatted(xml(getModuleId().get()), xml(version), xml(getModuleName().get()), xml(getVendor().get()),
                xml(getModuleDescription().get()));
    }

    private static void put(final ZipOutputStream zip, final String name, final byte[] bytes) throws IOException {
        final ZipEntry e = new ZipEntry(name);
        e.setTime(FIXED_TIME);
        zip.putNextEntry(e);
        final OutputStream o = zip;
        o.write(bytes);
        zip.closeEntry();
    }

    private static String xml(final String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
