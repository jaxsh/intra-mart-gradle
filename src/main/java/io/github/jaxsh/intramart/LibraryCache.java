package io.github.jaxsh.intramart;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 编译用的 intra-mart jar：只取各模块 imm 里最终落到 WAR 的 WEB-INF/lib 的 jar（jar 构件，以及 webapp・public 构件里的
 * WEB-INF/lib 下的 jar；vendor 模块的第三方 jar 都在后者），不展开整个平台。
 * 缓存按模块：&lt;cache&gt;/&lt;id&gt;/&lt;version&gt;/*.jar，多个工程、多个平台版本共用（没升级的模块不重复存）。
 * 选哪些、同名谁赢与 {@link ImAssembler} 一致：按 module-order，后面的覆盖前面的；删 uow_api-6.jar，resin 时删 jboss-logging。
 */
final class LibraryCache {

    private static final String DONE = ".complete";

    private static final String LIB = "WEB-INF/lib/";

    /** 展开到 WAR 根的种别（{@link ImAssembler} 里落点为 "" 的）。 */
    private static final java.util.Set<String> WAR_ROOT_KINDS = java.util.Set.of("webapp", "public");

    private LibraryCache() {
    }

    /** 返回最终 WEB-INF/lib 里平台的那些 jar（缓存里的路径，按 module-order）。 */
    static List<Path> resolve(final Path repository, final Path jugglingProject, final Path cache, final String runtime,
            final boolean samples) throws IOException {
        final JugglingProject project = JugglingProject.read(jugglingProject.resolve("juggling.im"));
        final Map<String, Path> byName = new LinkedHashMap<>();
        for (final JugglingProject.Module m : project.moduleOrder()) {
            if (m.pack()) {
                continue;
            }
            final Path dir = repository.resolve(m.id()).resolve(m.version());
            if (!ImAssembler.isValidModule(Files.readString(dir.resolve("module.xml"), StandardCharsets.UTF_8), runtime)) {
                continue;
            }
            final Path out = cache.resolve(m.id()).resolve(m.version());
            if (!Files.isRegularFile(out.resolve(DONE))) {
                extract(dir.resolve(m.id() + "-" + m.version() + ".imm"), out);
            }
            // .complete 一行一个：main|sample<TAB>jar 名
            for (final String line : Files.readAllLines(out.resolve(DONE), StandardCharsets.UTF_8)) {
                final String[] kv = line.split("\t", 2);
                if (kv.length < 2 || "sample".equals(kv[0]) && !samples) {
                    continue;
                }
                byName.remove(kv[1]);
                byName.put(kv[1], out.resolve(kv[1]));
            }
        }
        byName.remove("uow_api-6.jar");
        if ("resin".equals(runtime)) {
            byName.keySet().removeIf(n -> n.startsWith("jboss-logging-") && n.endsWith(".jar"));
        }
        return new ArrayList<>(byName.values());
    }

    /** 先写临时目录再改名：两个工程同时取同一模块，或中途中断，都不会留下半套。 */
    private static void extract(final Path imm, final Path out) throws IOException {
        final Path tmp = out.resolveSibling(out.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.createDirectories(tmp);
        final StringBuilder done = new StringBuilder();
        try (ZipFile zip = new ZipFile(imm.toFile())) {
            for (final Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements();) {
                final ZipEntry e = en.nextElement();
                if (e.isDirectory()) {
                    continue;
                }
                final String name = ImAssembler.baseName(e.getName());
                final Matcher a = ImAssembler.ARTIFACT.matcher(name);
                if (!a.matches()) {
                    continue;
                }
                if ("jar".equals(a.group(3))) {
                    try (InputStream in = zip.getInputStream(e)) {
                        Files.copy(in, tmp.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                    }
                    done.append(a.group(1)).append('\t').append(name).append('\n');
                } else if (WAR_ROOT_KINDS.contains(a.group(2))) {
                    // webapp・public 展开到 WAR 根：其中的 WEB-INF/lib/*.jar 也是平台的库（vendor 模块都这样带第三方 jar）
                    try (ZipInputStream z = new ZipInputStream(zip.getInputStream(e))) {
                        for (ZipEntry n; (n = z.getNextEntry()) != null;) {
                            final String p = n.getName();
                            if (!n.isDirectory() && p.startsWith(LIB) && p.endsWith(".jar") && p.indexOf('/', LIB.length()) < 0) {
                                final String jar = p.substring(LIB.length());
                                Files.copy(z, tmp.resolve(jar), StandardCopyOption.REPLACE_EXISTING);
                                done.append(a.group(1)).append('\t').append(jar).append('\n');
                            }
                        }
                    }
                }
            }
        }
        Files.writeString(tmp.resolve(DONE), done, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE);
        } catch (final IOException e) {
            if (!Files.isRegularFile(out.resolve(DONE))) {
                throw e;
            }
            // 别的工程先放好了
        } finally {
            ImAssembler.deleteRecursively(tmp);
        }
    }
}
