package io.github.jaxsh.intramart;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 官方 Maven 仓库（https://maven.intra-mart.jp/，匿名）的 BOM jp.co.intra_mart:im_bom:8.0.0-&lt;release&gt;：
 * 由平台版本选 BOM（不让用户写 2026-autumn 这类发布名）。
 * BOM 列的是 Maven 构件（artifactId ＝ 模块 ID 去掉 jp.co.intra_mart.），版本与 imm 一致；仓库没有 sources・javadoc jar。
 * BOM 版本必须在配置阶段定：Gradle 建任务图时就解析编译依赖，留到 imSetup 之后依赖就解析不到。
 */
final class MavenBom {

    /** 缺省：官方 Maven 仓库（匿名）。intraMart.mavenRepositoryUrl 可换成公司的代理仓库等。 */
    static final String DEFAULT_REPOSITORY = "https://maven.intra-mart.jp/";

    private static final String BOM_PATH = "jp/co/intra_mart/im_bom/";

    private static final Pattern VERSION = Pattern.compile("<version>([^<]+)</version>");

    private static final Pattern MANAGED = Pattern.compile(
            "<groupId>jp\\.co\\.intra_mart</groupId>\\s*<artifactId>([^<]+)</artifactId>\\s*<version>([^<]+)</version>");

    private static final String PARENT_PATH = "jp/co/intra_mart/parent/";

    private static final Pattern EXCLUSION = Pattern.compile(
            "<exclusion>\\s*<groupId>([^<]+)</groupId>\\s*<artifactId>([^<]+)</artifactId>\\s*</exclusion>");

    private final HttpClient http = Http.client();

    private final Path cache;

    private final String repository;

    MavenBom(final Path cache, final String repository) {
        this.repository = repository.endsWith("/") ? repository : repository + "/";
        // 按仓库分缓存：换了仓库（如公司的代理仓库）不读别处的旧缓存
        this.cache = cache.resolve(this.repository.replaceAll("^https?://", "").replaceAll("[^A-Za-z0-9.-]+", "_"));
    }

    /**
     * 平台版本（如 8.0.40）→ BOM 版本（如 8.0.0-2026-autumn）：BOM 里 im_tenant 的版本与平台版本相同
     * （2025-spring〜2026-autumn 各版都如此）。配置阶段就要定（Gradle 建任务图时解析编译依赖），所以不靠 imSetup 的结果。
     */
    String forBase(final String baseVersion) throws IOException {
        final StringBuilder tried = new StringBuilder();
        for (final String bom : candidates()) {
            final String tenant = managed(bom).get("im_tenant");
            if (baseVersion.equals(tenant)) {
                return bom;
            }
            tried.append(' ').append(bom).append("(im_tenant ").append(tenant).append(')');
        }
        throw new IOException("No jp.co.intra_mart:im_bom in " + repository + " for intra-mart " + baseVersion + ";" + tried);
    }

    /** juggling.im 的模块与 BOM 版本不一致的（补丁号不计），用于提醒。 */
    List<String> differences(final Path jugglingIm, final String bom) throws IOException {
        final Map<String, String> managed = managed(bom);
        final List<String> out = new ArrayList<>();
        for (final JugglingProject.Module m : JugglingProject.read(jugglingIm).moduleOrder()) {
            if (m.pack() || !m.id().startsWith(PlatformResolver.PREFIX)) {
                continue;
            }
            final String want = managed.get(m.id().substring(PlatformResolver.PREFIX.length()));
            if (want != null && !base(want).equals(base(m.version()))) {
                out.add(m.id() + " " + m.version() + " (BOM " + want + ")");
            }
        }
        return out;
    }

    /**
     * 官方 parent POM（jp.co.intra_mart:parent:8.0.x-&lt;release&gt;，与 BOM 同一 release）dependencyManagement 里的
     * exclusions：intra-mart 模块的传递依赖中在公开仓库取不到的构件（com.caucho:resin、openrules、poi_v3_9、supercsv 等）。
     * 返回 {group, module}，module 为 "*" 时整个 group。官方 Maven 工程继承 parent 自然带上；Gradle 侧按配置排除，效果相同。
     */
    List<String[]> exclusions(final String bom) throws IOException {
        final String release = bom.substring(bom.indexOf('-') + 1);
        String parent = null;
        final Matcher v = VERSION.matcher(text(PARENT_PATH + "maven-metadata.xml", false));
        while (v.find()) {
            if (v.group(1).endsWith("-" + release)) {
                parent = v.group(1); // 同 release 多个时取最后（新的在后）
            }
        }
        if (parent == null) {
            throw new IOException("No jp.co.intra_mart:parent for release " + release + " in " + repository);
        }
        final List<String[]> out = new ArrayList<>();
        final Matcher e = EXCLUSION.matcher(text(PARENT_PATH + parent + "/parent-" + parent + ".pom", true));
        while (e.find()) {
            out.add(new String[] {e.group(1), e.group(2)});
        }
        return out;
    }

    private List<String> candidates() throws IOException {
        final List<String> out = new ArrayList<>();
        final Matcher v = VERSION.matcher(text(BOM_PATH + "maven-metadata.xml", false));
        while (v.find()) {
            if (v.group(1).startsWith("8.0.0-")) {
                out.add(0, v.group(1)); // 新的在后：倒过来先试新的
            }
        }
        return out;
    }

    private Map<String, String> managed(final String bom) throws IOException {
        final Map<String, String> m = new HashMap<>();
        final Matcher d = MANAGED.matcher(text(BOM_PATH + bom + "/im_bom-" + bom + ".pom", true));
        while (d.find()) {
            m.put(d.group(1), d.group(2));
        }
        return m;
    }

    private static String base(final String version) {
        final int i = version.indexOf("-PATCH_");
        return i < 0 ? version : version.substring(0, i);
    }

    /** 带版本的 pom 不会变，缓存；maven-metadata.xml 缓存一天（配置阶段要用，不能每次构建都上网），取不到时用旧的。 */
    private String text(final String path, final boolean immutable) throws IOException {
        final Path f = cache.resolve(path);
        if (Files.isRegularFile(f) && (immutable
                || Files.getLastModifiedTime(f).toMillis() > System.currentTimeMillis() - Duration.ofDays(1).toMillis())) {
            return Files.readString(f, StandardCharsets.UTF_8);
        }
        try {
            final HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(repository + path))
                    .timeout(Duration.ofSeconds(60)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (r.statusCode() != 200) {
                throw new IOException("HTTP " + r.statusCode() + ": " + repository + path);
            }
            Files.createDirectories(f.getParent());
            Files.write(f, r.body());
            return new String(r.body(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            if (Files.isRegularFile(f)) {
                return Files.readString(f, StandardCharsets.UTF_8);
            }
            throw e;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }
}
