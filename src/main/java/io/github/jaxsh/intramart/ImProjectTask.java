package io.github.jaxsh.intramart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;
import org.gradle.api.tasks.options.Option;

/**
 * 生成相当于 IM-Juggling 工程的目录：juggling.im（按版本解决的模块与 module-order）＋叠进 WEB-INF 的文件
 * （resin-web.xml 的数据源、conf/storage-config.xml 的 storage 路径），并把要用的 imm 取进缓存。
 */
@DisableCachingByDefault(because = "fetches from remote repositories into a shared cache")
public abstract class ImProjectTask extends DefaultTask {

    @Input
    public abstract Property<String> getBaseId();

    @Input
    public abstract Property<String> getBaseVersion();

    @Input
    public abstract ListProperty<String> getApplications();

    @Input
    public abstract MapProperty<String, String> getApplicationVersions();

    @Input
    public abstract SetProperty<String> getExcludes();

    @Input
    public abstract SetProperty<String> getIncludes();

    @Input
    public abstract Property<Boolean> getPatches();

    @Input
    public abstract SetProperty<String> getPatchModules();

    @Input
    public abstract Property<String> getRepositoryUrl();

    @Internal
    public abstract DirectoryProperty getCacheDir();

    @Internal
    public abstract ConfigurableFileCollection getMirrors();

    @OutputDirectory
    public abstract DirectoryProperty getProjectDir();

    @OutputFile
    public abstract RegularFileProperty getReport();

    private boolean refresh;

    @Option(option = "refresh", description = "Re-download the repository catalogs and version index (module lists, metadata.zip) to pick up new patches")
    public void setRefresh(final boolean refresh) {
        this.refresh = refresh;
    }

    @Internal
    public boolean isRefresh() {
        return refresh;
    }

    @TaskAction
    public void generate() throws IOException {
        final List<Path> mirrors = new ArrayList<>();
        getMirrors().getFiles().forEach(f -> mirrors.add(f.toPath()));
        final ModuleRepository repo = new ModuleRepository(getCacheDir().get().getAsFile().toPath(), mirrors,
                getRepositoryUrl().get(), msg -> getLogger().lifecycle(msg));
        final PlatformResolver.Result r = new PlatformResolver(repo, msg -> getLogger().lifecycle(msg)).resolve(
                getBaseId().get(), getBaseVersion().get(), getApplications().get(), getApplicationVersions().get(),
                getExcludes().get(), getIncludes().get(),
                new PlatformResolver.Patches(getPatches().get(), getPatchModules().get()), refresh);

        final Path dir = getProjectDir().get().getAsFile().toPath();
        ImAssembler.deleteRecursively(dir);
        Files.createDirectories(dir);
        JugglingImWriter.write(r, "intra-mart-gradle: " + r.base().name(), dir.resolve("juggling.im"));

        int n = 0;
        for (final ModuleInfo m : r.moduleOrder()) {
            repo.imm(m.id(), m.version());
            if (++n % 50 == 0) {
                getLogger().lifecycle("imm ready {}/{}", n, r.moduleOrder().size());
            }
        }

        Files.writeString(dir.resolve("resin-web.xml"), resinWebXml(), StandardCharsets.UTF_8);
        final Path conf = dir.resolve("conf");
        Files.createDirectories(conf);
        Files.writeString(conf.resolve("storage-config.xml"), storageConfig(repo, r), StandardCharsets.UTF_8);

        writeReport(r);
        getLogger().lifecycle("{} {} + {} application packs: {} modules selected, {} patched, module-order {} entries -> {}", r.base().id(), r.base().version(),
                r.applications().size(), r.selected().size(), r.patched().size(), r.moduleOrder().size(), dir);
    }

    /**
     * Juggling 样例工程的 resin-web.xml，去掉了数据源：WAR 与环境无关，jdbc/default 由各环境的 Resin（本机是 imResinConfig
     * 生成的 resin.xml）定义。
     */
    private static String resinWebXml() {
        return """
                <web-app xmlns="http://caucho.com/ns/resin" xmlns:resin="urn:java:com.caucho.resin">
                    <character-encoding>UTF-8</character-encoding>

                    <log-handler name="" class="jp.co.intra_mart.common.platform.log.handler.JDKLoggingOverIntramartLoggerHandler"/>
                    <logger name="debug.com.sun.portal" level="warning" />

                	<!-- im_service(im_asynchronous) -->
                	<resource jndi-name="jca/work" type="jp.co.intra_mart.system.asynchronous.impl.executor.work.resin.ResinResourceAdapter" />
                	<jsp>
                		<recycle-tags>false</recycle-tags>
                	</jsp>
                	<session-config>
                	    <reuse-session-id>false</reuse-session-id>
                		<session-timeout>30</session-timeout>
                	</session-config>

                	<mime-mapping extension=".json" mime-type="application/json"/>
                </web-app>
                """;
    }

    /** storage 根的 JVM 系统属性：WAR 里只写 ${im.storage.dir}，路径由各环境的 Resin 用 -Dim.storage.dir=… 给。 */
    public static final String STORAGE_PROPERTY = "im.storage.dir";

    /** 模块带的 conf/storage-config.xml（module-order 里最后一个提供者，与展开时覆盖规则一致），root-path-name 换成 ${im.storage.dir}。 */
    private String storageConfig(final ModuleRepository repo, final PlatformResolver.Result r) throws IOException {
        String found = null;
        for (final ModuleInfo m : r.moduleOrder()) {
            if (m.pack()) {
                continue;
            }
            try (ZipInputStream z = new ZipInputStream(Files.newInputStream(repo.imm(m.id(), m.version())))) {
                for (ZipEntry e; (e = z.getNextEntry()) != null;) {
                    if (!e.getName().endsWith("-main-conf.zip")) {
                        continue;
                    }
                    final ZipInputStream c = new ZipInputStream(z);
                    for (ZipEntry ce; (ce = c.getNextEntry()) != null;) {
                        if ("storage-config.xml".equals(ce.getName())) {
                            found = new String(readAll(c), StandardCharsets.UTF_8);
                        }
                    }
                }
            }
        }
        if (found == null) {
            throw new IOException("No module provides conf/storage-config.xml");
        }
        final Matcher m = Pattern.compile("<root-path-name>[^<]*</root-path-name>").matcher(found);
        if (!m.find()) {
            throw new IOException("storage-config.xml has no root-path-name");
        }
        return found.substring(0, m.start()) + "<root-path-name>${" + STORAGE_PROPERTY + "}</root-path-name>" + found.substring(m.end());
    }

    private void writeReport(final PlatformResolver.Result r) throws IOException {
        final StringBuilder sb = new StringBuilder();
        sb.append("base         ").append(r.base().id()).append(' ').append(r.base().version()).append("  ").append(r.base().name()).append('\n');
        for (final ModuleInfo a : r.applications()) {
            sb.append("application  ").append(a.id()).append(' ').append(a.version()).append("  ").append(a.name()).append('\n');
        }
        sb.append("modules ").append(r.all().size()).append(", selected ").append(r.selected().size())
                .append(", patched ").append(r.patched().size()).append('\n');
        sb.append("\n## Patches (").append(r.patched().size()).append(")\n");
        for (final var e : r.patched().entrySet()) {
            sb.append(e.getKey()).append(' ').append(e.getValue()).append('\n');
        }
        sb.append("\n## Not selected (").append(r.all().size() - r.selected().size()).append(")\n");
        for (final ModuleInfo m : r.all()) {
            if (!r.selected().contains(m.id())) {
                sb.append(m.id()).append(' ').append(m.version()).append("  ").append(r.excludedBecause().getOrDefault(m.id(), "")).append('\n');
            }
        }
        sb.append("\n## module-order (").append(r.moduleOrder().size()).append(")\n");
        for (final ModuleInfo m : r.moduleOrder()) {
            sb.append(m.id()).append(' ').append(m.version()).append('\n');
        }
        final Path p = getReport().get().getAsFile().toPath();
        Files.createDirectories(p.getParent());
        Files.writeString(p, sb, StandardCharsets.UTF_8);
    }

    private static byte[] readAll(final InputStream in) throws IOException {
        final ByteArrayOutputStream b = new ByteArrayOutputStream();
        in.transferTo(b);
        return b.toByteArray();
    }

    private static String xml(final String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

}
