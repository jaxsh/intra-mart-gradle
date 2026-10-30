package io.github.jaxsh.intramart;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 把 Juggling 工程（juggling.im＋覆盖文件）按模块仓库展开成 WAR 目录，等同官方 IM-Juggling 的 WAR 出力（resin40 模板）。
 * 规则照官方开源的 @intra-mart/juggling-core 0.1.5（LGPL-3.0） dist/build/{expansion,archive-recipe,export-war,env-txt,project-overlay}.js：
 * 展开模块 → juggling.im → juggling.properties → web.xml → im_env.txt/env.txt → 工程文件覆盖
 * → 删 uow_api-6.jar・highcharts（trial）・jboss-logging（resin）→ 模块附带脚本。
 * 同一路径多个模块提供时不合并：按 module-order 后展开的覆盖先展开的；同一模块内按 imm 的 zip 条目顺序。
 */
public final class ImAssembler {

    /** 拼装参数。 */
    public record Options(Path repository, Path project, Path dest, String runtime, String env, boolean samples,
            boolean trial, Consumer<String> log, Consumer<String> progress) {
    }

    /** 重新展开时不删的（intra-mart 运行时写的日志・报告）；WEB-INF/work・tmp 是旧平台的编译结果，照删。 */
    static final List<String> PRESERVE = List.of("WEB-INF/log", "WEB-INF/report");

    // 构件名（juggling-core dist/model/artifact-types.js）：<名>-main|sample-<种别>.zip，jar 为 <名>-main|sample.jar
    static final Pattern ARTIFACT = Pattern.compile("^[^\\s]+-(main|sample)(?:-([a-z-]+))?\\.(jar|zip)$");

    // 种别 → WAR 内位置（juggling-core expansion.js 的 WAR_PLACEMENT_MAP）；java-external・jssp-internal 不展开
    private static final Map<String, String> KIND_DIR = Map.of(
            "jar", "WEB-INF/lib",
            "conf", "WEB-INF/conf",
            "jssp-external", "WEB-INF/jssp",
            "plugin", "WEB-INF/plugin",
            "resouces", "WEB-INF/classes",
            "schema", "WEB-INF/schema",
            "webapp", "",
            "storage", "WEB-INF/storage",
            "public", "");

    private static final Pattern TAG = Pattern.compile("<tag>([^<]+)</tag>");

    private final Options o;

    /** 进度用：写出的文件数。 */
    private long files;

    public ImAssembler(final Options o) {
        this.o = o;
    }

    public void assemble() throws IOException {
        final Path jugglingIm = o.project().resolve("juggling.im");
        final JugglingProject project = JugglingProject.read(jugglingIm);
        clearExcept(o.dest(), PRESERVE);

        final List<Script> scripts = new ArrayList<>();
        // 进度：每 10 秒一行（3 万多个文件，Defender 扫描时要十几分钟，不打的话看着像卡住）
        final int total = project.moduleOrder().size();
        final long start = System.currentTimeMillis();
        long lastReport = start;
        int done = 0;
        for (final JugglingProject.Module m : project.moduleOrder()) {
            final long now = System.currentTimeMillis();
            if (now - lastReport >= 10_000) {
                lastReport = now;
                o.progress().accept(String.format("Expanding modules %d/%d (%d files written, %s)", done, total, files, elapsed(start)));
            }
            done++;
            final Path dir = o.repository().resolve(m.id()).resolve(m.version());
            final Path imm = dir.resolve(m.id() + "-" + m.version() + ".imm");
            if (!Files.isRegularFile(imm)) {
                throw new IOException("Module not found in repository: " + imm);
            }
            if (m.pack()) {
                extractPack(imm);
                continue;
            }
            final String moduleXml = Files.readString(dir.resolve("module.xml"), StandardCharsets.UTF_8);
            if (!isValidModule(moduleXml, o.runtime())) {
                o.log().accept("Skipped (runtime does not match): " + m.id() + "-" + m.version());
                continue;
            }
            extractModule(imm);
            final Script s = Script.find(m, moduleXml);
            if (s != null) {
                scripts.add(s);
            }
        }

        o.progress().accept(String.format("Expanded %d modules (%d files written, %s)", total, files, elapsed(start)));

        final Path webInf = o.dest().resolve("WEB-INF");
        Files.copy(jugglingIm, webInf.resolve("juggling.im"), StandardCopyOption.REPLACE_EXISTING);
        writeJugglingProperties(webInf.resolve("juggling.properties"));
        JugglingRecords.write(jugglingIm, o.dest());
        WebXmlBuilder.build(readEntry(baseImm(project), "web.xml"), project, o.runtime(), webInf.resolve("web.xml"));
        writeEnvFiles(webInf.resolve("classes"));
        overwriteProjectFiles(webInf);

        Files.deleteIfExists(webInf.resolve("lib/uow_api-6.jar"));
        if (o.trial()) {
            deleteRecursively(o.dest().resolve("highcharts"));
            Files.deleteIfExists(webInf.resolve("conf/viewcreator-graph-library-config/im-highcharts.xml"));
        }
        if ("resin".equals(o.runtime())) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(webInf.resolve("lib"), "jboss-logging-*.jar")) {
                for (final Path p : ds) {
                    Files.delete(p);
                }
            }
        }
        for (final Script s : scripts) {
            s.run(o.dest());
        }
    }

    private Path baseImm(final JugglingProject project) {
        final JugglingProject.Module b = project.base();
        return o.repository().resolve(b.id()).resolve(b.version()).resolve(b.id() + "-" + b.version() + ".imm");
    }

    /** module.xml 的第一个 app:X tag 决定：X 含 runtime 才展开；没有 app: tag 一律展开（juggling-core expansion.js 的 isModuleForApplicationServer）。 */
    static boolean isValidModule(final String moduleXml, final String runtime) {
        final Matcher t = TAG.matcher(moduleXml);
        while (t.find()) {
            final String tag = t.group(1).trim();
            if (tag.startsWith("app:")) {
                return tag.substring("app:".length()).contains(runtime);
            }
        }
        return true;
    }

    /** module-pack：imm 里的 .iar 放进 WEB-INF/conf/products。 */
    private void extractPack(final Path imm) throws IOException {
        final Path products = o.dest().resolve("WEB-INF/conf/products");
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(imm))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null;) {
                if (!e.isDirectory() && e.getName().endsWith(".iar")) {
                    copy(zin, products.resolve(baseName(e.getName())));
                }
            }
        }
    }

    /** 叶模块：imm 里的构件按种别展开，同一路径后写的覆盖先写的。 */
    private void extractModule(final Path imm) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(imm))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null;) {
                if (e.isDirectory()) {
                    continue;
                }
                final String name = baseName(e.getName());
                final Matcher m = ARTIFACT.matcher(name);
                if (!m.matches()) {
                    continue;
                }
                if ("sample".equals(m.group(1)) && !o.samples()) {
                    continue;
                }
                final String kind = "jar".equals(m.group(3)) ? "jar" : m.group(2);
                final String dir = kind == null ? null : KIND_DIR.get(kind);
                if (dir == null) {
                    continue;
                }
                final Path target = dir.isEmpty() ? o.dest() : o.dest().resolve(dir);
                if ("jar".equals(kind)) {
                    copy(zin, target.resolve(name));
                } else {
                    unzip(zin, target);
                }
            }
        }
    }

    private static String elapsed(final long start) {
        final long s = (System.currentTimeMillis() - start) / 1000;
        return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }

    private void unzip(final InputStream in, final Path target) throws IOException {
        final ZipInputStream z = new ZipInputStream(in);
        for (ZipEntry e; (e = z.getNextEntry()) != null;) {
            final Path p = target.resolve(e.getName()).normalize();
            if (!p.startsWith(target)) {
                throw new IOException("Zip entry escapes target directory: " + e.getName());
            }
            // 目录条目不建（官方产物里只有文件，空目录不进 WAR）；文件的上级目录照建
            if (!e.isDirectory()) {
                copy(z, p);
            }
        }
        // 不关 z：底层是外层 imm 的流
    }

    private void copy(final InputStream in, final Path p) throws IOException {
        files++;
        Files.createDirectories(p.getParent());
        try (OutputStream out = Files.newOutputStream(p)) {
            in.transferTo(out);
        }
    }

    private static byte[] readEntry(final Path zip, final String entry) throws IOException {
        try (ZipInputStream zin = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = zin.getNextEntry()) != null;) {
                if (entry.equals(e.getName())) {
                    final ByteArrayOutputStream b = new ByteArrayOutputStream();
                    zin.transferTo(b);
                    return b.toByteArray();
                }
            }
        }
        throw new IOException(entry + " not found in " + zip);
    }

    private void writeJugglingProperties(final Path p) throws IOException {
        final Properties props = new Properties();
        props.setProperty("samples", String.valueOf(o.samples()));
        props.setProperty("trial", String.valueOf(o.trial()));
        try (OutputStream out = Files.newOutputStream(p)) {
            props.store(out, "");
        }
    }

    /** juggling-core env-txt.js 的 ENV_TXT_TABLE：ut→ut/ut，si→si/ct，pt→pt/it，production→production/product。 */
    private void writeEnvFiles(final Path classes) throws IOException {
        final String env;
        final String envTxt;
        switch (o.env()) {
        case "ut" -> { env = "ut"; envTxt = "ut"; }
        case "si" -> { env = "si"; envTxt = "ct"; }
        case "pt" -> { env = "pt"; envTxt = "it"; }
        case "production" -> { env = "production"; envTxt = "product"; }
        default -> throw new IOException("env must be one of ut|si|pt|production: " + o.env());
        }
        Files.createDirectories(classes);
        Files.writeString(classes.resolve("im_env.txt"), env, StandardCharsets.UTF_8);
        Files.writeString(classes.resolve("env.txt"), envTxt, StandardCharsets.UTF_8);
    }

    /** 工程目录里除 juggling.im 与 modules/ 外的文件叠进 WEB-INF。 */
    private void overwriteProjectFiles(final Path webInf) throws IOException {
        final Path root = o.project();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path d, final BasicFileAttributes a) {
                return d.equals(root.resolve("modules")) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path f, final BasicFileAttributes a) throws IOException {
                if (!f.equals(root.resolve("juggling.im"))) {
                    final Path t = webInf.resolve(root.relativize(f).toString());
                    Files.createDirectories(t.getParent());
                    Files.copy(f, t, StandardCopyOption.REPLACE_EXISTING);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /** 清空 dir，留下 keep 里的子路径（相对 dir）。 */
    static void clearExcept(final Path dir, final List<String> keep) throws IOException {
        Files.createDirectories(dir);
        final List<Path> kept = keep.stream().map(dir::resolve).toList();
        Files.walkFileTree(dir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(final Path d, final BasicFileAttributes a) {
                return kept.contains(d) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(final Path f, final BasicFileAttributes a) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path d, final IOException e) throws IOException {
                if (!d.equals(dir) && kept.stream().noneMatch(k -> k.startsWith(d))) {
                    Files.delete(d);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    static String baseName(final String entry) {
        final int i = entry.lastIndexOf('/');
        return i < 0 ? entry : entry.substring(i + 1);
    }

    static void deleteRecursively(final Path p) throws IOException {
        if (!Files.exists(p)) {
            return;
        }
        Files.walkFileTree(p, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(final Path f, final BasicFileAttributes a) throws IOException {
                Files.delete(f);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(final Path d, final IOException e) throws IOException {
                Files.delete(d);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * 模块 module.xml 里的 {@code s:script type="text/xml+ant"}。官方在 WAR 目录跑 ant（juggling-core external-scripts.js 交给外部 port）；
     * 8.0.38〜8.0.40 只有 im_ui 有（imui 随机键），这里不跑 ant 与 Nashorn，按已知内容用 Java 实现，遇到不认识的就停。
     */
    private record Script(JugglingProject.Module module) {
        private static final String IMUI_FILE = "WEB-INF/conf/ui-tag-config/ui-tag-config_imui-picture.xml";
        private static final String IMUI_TOKEN = "<param-value>^^^intra-mart^^^</param-value>";
        private static final SecureRandom RANDOM = new SecureRandom();

        static Script find(final JugglingProject.Module m, final String moduleXml) throws IOException {
            if (!moduleXml.contains("text/xml+ant")) {
                return null;
            }
            if ("jp.co.intra_mart.im_ui".equals(m.id()) && moduleXml.contains("imui-key")
                    && moduleXml.contains("ui-tag-config_imui-picture.xml")) {
                return new Script(m);
            }
            throw new IOException("Unsupported module script (needs an implementation based on module.xml): " + m.id() + "-" + m.version());
        }

        void run(final Path dest) throws IOException {
            // 原脚本：new Date().getTime().toString(36) + Math.random().toString(36).slice(-8)
            final StringBuilder rnd = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                rnd.append(Character.forDigit(RANDOM.nextInt(36), 36));
            }
            final String key = Long.toString(System.currentTimeMillis(), 36) + rnd;
            final Path f = dest.resolve(IMUI_FILE);
            final String s = Files.readString(f, StandardCharsets.UTF_8);
            Files.writeString(f, s.replace(IMUI_TOKEN, "<param-value>" + key + "</param-value>"), StandardCharsets.UTF_8);
        }
    }
}
