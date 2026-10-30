package io.github.jaxsh.intramart;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * 可直接部署的 WAR 目录（相当于官方 Resin 的 webapps/imart）：
 * <ol>
 * <li>平台：juggling.im 等变了（或第一次）才从 imm 整个展开，否则不动；</li>
 * <li>叠加：本工程 jar・依赖 → WEB-INF/lib，src/main 下的 conf・schema・plugin・jssp・storage・public・webapp 按 {@link ModuleLayout}
 * 落到 WAR 里（与把本工程打成ユーザモジュール再展开的结果相同）。只复制变了的；不再叠加的文件删掉，
 * 若它原本覆盖的是平台文件，就重新展开平台以恢复原样（少见）。</li>
 * </ol>
 * 平台不另存一份：工程里只有这一份 3 万多个文件，且在 build/ 下。状态记在 stateDir（WAR 外）。
 */
@DisableCachingByDefault(because = "maintains a large directory in place")
public abstract class WebappTask extends DefaultTask {

    /** 展开规则的版本：规则改了才加 1，强制各工程重新展开（插件别的改动不触发）。 */
    static final String FORMAT = "2";

    @Internal
    public abstract DirectoryProperty getRepository();

    @Internal
    public abstract DirectoryProperty getJugglingProject();

    @Internal
    public abstract Property<String> getRuntime();

    @Internal
    public abstract Property<String> getEnv();

    @Internal
    public abstract Property<Boolean> getSamples();

    @Internal
    public abstract Property<Boolean> getTrial();

    /** 进 WEB-INF/lib 的：本工程 jar 与 runtimeClasspath（声明为输入是为了带上构建它们的任务）。 */
    @InputFiles
    @PathSensitive(PathSensitivity.NAME_ONLY)
    public abstract ConfigurableFileCollection getLibraries();

    /** src/main：下面按 {@link ModuleLayout#DIRS} 取各目录（都可以没有）。 */
    @Internal
    public abstract DirectoryProperty getSourceRoot();

    @Internal
    public abstract DirectoryProperty getDestination();

    @Internal
    public abstract DirectoryProperty getStateDir();

    public WebappTask() {
        getOutputs().upToDateWhen(t -> false);
    }

    @TaskAction
    public void run() throws IOException {
        final Path dest = getDestination().get().getAsFile().toPath();
        final Path state = getStateDir().get().getAsFile().toPath();
        Files.createDirectories(state);
        final Path stampFile = state.resolve("platform.stamp");
        final Path platformFilesFile = state.resolve("platform-files.txt");
        final Path overlayFile = state.resolve("overlay.txt");

        final String key = platformKey();
        final Map<String, Path> overlay = overlay();
        String reason = null;
        if (!Files.isRegularFile(stampFile)) {
            reason = "first run";
        } else if (!key.equals(Files.readString(stampFile, StandardCharsets.UTF_8))) {
            reason = "platform changed";
        }
        Map<String, String> previous = reason == null ? readOverlay(overlayFile) : Map.of();
        final List<String> removed = new ArrayList<>();
        if (reason == null) {
            final Set<String> platformFiles = new HashSet<>(Files.readAllLines(platformFilesFile, StandardCharsets.UTF_8));
            for (final String rel : previous.keySet()) {
                if (!overlay.containsKey(rel)) {
                    if (platformFiles.contains(rel)) {
                        reason = "restoring platform file no longer overridden: " + rel;
                        break;
                    }
                    removed.add(rel);
                }
            }
        }
        if (reason != null) {
            getLogger().lifecycle("Expanding the intra-mart platform into {} ({})", dest, reason);
            Files.deleteIfExists(stampFile); // 中途中断的话下次重来
            new ImAssembler(new ImAssembler.Options(
                    getRepository().get().getAsFile().toPath(),
                    getJugglingProject().get().getAsFile().toPath(),
                    dest,
                    getRuntime().get(),
                    getEnv().get(),
                    getSamples().get(),
                    getTrial().get(),
                    msg -> getLogger().info(msg),
                    msg -> getLogger().lifecycle(msg))).assemble();
            Files.write(platformFilesFile, listFiles(dest), StandardCharsets.UTF_8);
            Files.writeString(stampFile, key, StandardCharsets.UTF_8);
            previous = Map.of();
            removed.clear();
        }

        // Resin 开着时（Windows）WEB-INF/lib 的 jar 被占用，换不了：跳过并提醒，记录里留旧的，下次再试；
        // 其余文件（JSP・JS・CSS 等）照常同步，Resin 开着也能生效
        final Map<String, String> now = new TreeMap<>();
        final List<String> locked = new ArrayList<>();
        for (final String rel : removed) {
            try {
                Files.deleteIfExists(dest.resolve(rel));
            } catch (final java.nio.file.FileSystemException ex) {
                locked.add(rel);
                now.put(rel, previous.get(rel));
            }
        }
        int copied = 0;
        for (final Map.Entry<String, Path> e : overlay.entrySet()) {
            final String sig = signature(e.getValue());
            final Path target = dest.resolve(e.getKey());
            if (!sig.equals(previous.get(e.getKey())) || !Files.exists(target)) {
                Files.createDirectories(target.getParent());
                try {
                    Files.copy(e.getValue(), target, StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                } catch (final java.nio.file.FileSystemException ex) {
                    locked.add(e.getKey());
                    if (previous.containsKey(e.getKey())) {
                        now.put(e.getKey(), previous.get(e.getKey()));
                    }
                    continue;
                }
            }
            now.put(e.getKey(), sig);
        }
        final List<String> lines = new ArrayList<>();
        now.forEach((k, v) -> lines.add(k + "\t" + v));
        Files.write(overlayFile, lines, StandardCharsets.UTF_8);
        getLogger().lifecycle("Exploded WAR {}: {} files from this project ({} copied, {} removed)",
                dest, overlay.size(), copied, removed.size() - locked.stream().filter(removed::contains).count());
        if (!locked.isEmpty()) {
            getLogger().warn("In use by the running Resin, not updated: {}. Restart (imRestart) to apply, "
                    + "or reload changed classes through the debugger (imDebug).", String.join(", ", locked));
        }
    }

    /** 平台由什么决定：Juggling 工程（juggling.im＋覆盖文件）的内容、runtime・env・samples・trial、展开规则版本。 */
    private String platformKey() throws IOException {
        final MessageDigest md = sha256();
        md.update(("format=" + FORMAT + "\nruntime=" + getRuntime().get() + "\nenv=" + getEnv().get()
                + "\nsamples=" + getSamples().get() + "\ntrial=" + getTrial().get() + "\n").getBytes(StandardCharsets.UTF_8));
        final Path root = getJugglingProject().get().getAsFile().toPath();
        for (final String rel : listFiles(root)) {
            md.update(rel.getBytes(StandardCharsets.UTF_8));
            md.update((byte) 0);
            md.update(Files.readAllBytes(root.resolve(rel)));
        }
        return HexFormat.of().formatHex(md.digest());
    }

    /** 本工程叠加的文件：WAR 内相对路径 → 来源。同一路径后写的赢（本工程 jar → 依赖 → {@link ModuleLayout#DIRS} 的顺序）。 */
    private Map<String, Path> overlay() throws IOException {
        final Map<String, Path> m = new LinkedHashMap<>();
        for (final File f : getLibraries().getFiles()) {
            if (f.isFile()) {
                m.put("WEB-INF/lib/" + f.getName(), f.toPath());
            }
        }
        final Path root = getSourceRoot().get().getAsFile().toPath();
        for (final ModuleLayout.Dir d : ModuleLayout.DIRS) {
            final Path src = root.resolve(d.source());
            if (Files.isDirectory(src)) {
                for (final String rel : listFiles(src)) {
                    m.put(d.warDir().isEmpty() ? rel : d.warDir() + "/" + rel, src.resolve(rel));
                }
            }
        }
        return m;
    }

    private static String signature(final Path p) throws IOException {
        final BasicFileAttributes a = Files.readAttributes(p, BasicFileAttributes.class);
        return p.toAbsolutePath() + "\t" + a.size() + "\t" + a.lastModifiedTime().toMillis();
    }

    private static Map<String, String> readOverlay(final Path f) throws IOException {
        final Map<String, String> m = new TreeMap<>();
        if (Files.isRegularFile(f)) {
            for (final String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                final int i = line.indexOf('\t');
                if (i > 0) {
                    m.put(line.substring(0, i), line.substring(i + 1));
                }
            }
        }
        return m;
    }

    /** dir 下所有文件的相对路径（/ 分隔、排序），不含 intra-mart 运行时写的 WEB-INF/log・work・tmp・report。 */
    static List<String> listFiles(final Path dir) throws IOException {
        final List<String> out = new ArrayList<>();
        final List<Path> skip = Stream.of("WEB-INF/log", "WEB-INF/work", "WEB-INF/tmp", "WEB-INF/report").map(dir::resolve).toList();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path d, final BasicFileAttributes a) {
                return skip.contains(d) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path f, final BasicFileAttributes a) {
                out.add(dir.relativize(f).toString().replace('\\', '/'));
                return FileVisitResult.CONTINUE;
            }
        });
        out.sort(null);
        return out;
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (final java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
