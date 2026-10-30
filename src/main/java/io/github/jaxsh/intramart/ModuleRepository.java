package io.github.jaxsh.intramart;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.function.Consumer;

/**
 * 模块仓库。本地缓存用 IM-Juggling 本地仓库布局（&lt;id&gt;/&lt;version&gt;/{&lt;id&gt;-&lt;version&gt;.imm, .imm.sha1, .vmr, module.xml,
 * module-pack.xml, message*.properties}），先看缓存，再看只读镜像（如 Juggling 的 workspace/.repository），最后取官方仓库
 * （http://repository.intra-mart.jp/&lt;base|app&gt;/&lt;id&gt;/&lt;version&gt;/…，匿名、只有 http）。imm 一律核 sha1。
 */
public final class ModuleRepository {

    private static final List<String> PARTITIONS = List.of("base", "app");

    private final Path cache;
    private final List<Path> mirrors;
    private final String baseUrl;
    private final Consumer<String> log;
    // 官方仓库只有 http：缺省的 HTTP/2 升级（h2c）会被断开（EOF reached while reading），Http 里固定 HTTP/1.1；走系统代理
    private final HttpClient http = Http.client();

    public ModuleRepository(final Path cache, final List<Path> mirrors, final String baseUrl, final Consumer<String> log) {
        this.cache = cache;
        this.mirrors = mirrors;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.log = log;
    }

    public Path root() {
        return cache;
    }

    public Path dir(final String id, final String version) {
        return cache.resolve(id).resolve(version);
    }

    /** 仓库根下的目录文件（base/base-module-list.xml 等），缓存在 &lt;cache&gt;/.catalog 下。 */
    public Path catalog(final String partition, final String name, final boolean refresh) throws IOException {
        final Path p = cache.resolve(".catalog").resolve(partition).resolve(name);
        if (refresh || !Files.isRegularFile(p)) {
            final byte[] b = get(baseUrl + "/" + partition + "/" + name, false);
            write(p, b);
        }
        return p;
    }

    /** module.xml（与 pack 的 module-pack.xml、message*.properties）在缓存里就绪，返回模块目录。 */
    public Path metadata(final String id, final String version) throws IOException {
        final Path dir = dir(id, version);
        if (Files.isRegularFile(dir.resolve("module.xml"))) {
            return dir;
        }
        if (copyFromMirror(id, version, false)) {
            return dir;
        }
        final String part = partition(id, version);
        final String url = baseUrl + "/" + part + "/" + id + "/" + version + "/";
        for (final String f : new String[] {"module-pack.xml", "message.properties", "message_ja.properties"}) {
            final byte[] b = get(url + f, true);
            if (b != null) {
                write(dir.resolve(f), b);
            }
        }
        write(dir.resolve(id + "-" + version + ".vmr"), ("HTTP=:" + baseUrl + "/" + part + "/").getBytes(StandardCharsets.UTF_8));
        write(dir.resolve("module.xml"), get(url + "module.xml", false));
        return dir;
    }

    /** imm 在缓存里就绪（核 sha1），返回 imm 路径。 */
    public Path imm(final String id, final String version) throws IOException {
        final Path dir = metadata(id, version);
        final Path imm = dir.resolve(id + "-" + version + ".imm");
        if (verified(imm)) {
            return imm;
        }
        if (copyFromMirror(id, version, true) && verified(imm)) {
            return imm;
        }
        final String url = baseUrl + "/" + partition(id, version) + "/" + id + "/" + version + "/" + id + "-" + version + ".imm";
        log.accept("Downloading " + url);
        write(Path.of(imm + ".sha1"), get(url + ".sha1", false));
        write(imm, get(url, false));
        if (!verified(imm)) {
            throw new IOException("sha1 mismatch: " + imm);
        }
        return imm;
    }

    private boolean copyFromMirror(final String id, final String version, final boolean needImm) throws IOException {
        for (final Path m : mirrors) {
            final Path src = m.resolve(id).resolve(version);
            if (!Files.isRegularFile(src.resolve("module.xml"))
                    || needImm && !Files.isRegularFile(src.resolve(id + "-" + version + ".imm"))) {
                continue;
            }
            final Path dst = dir(id, version);
            Files.createDirectories(dst);
            try (var s = Files.list(src)) {
                for (final Path f : (Iterable<Path>) s::iterator) {
                    if (Files.isRegularFile(f)) {
                        Files.copy(f, dst.resolve(f.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
                    }
                }
            }
            return true;
        }
        return false;
    }

    /** 模块在哪个分区：vmr 里记着就照它，没有就按 base→app 试 module.xml。 */
    private String partition(final String id, final String version) throws IOException {
        final Path vmr = dir(id, version).resolve(id + "-" + version + ".vmr");
        if (Files.isRegularFile(vmr)) {
            final String s = Files.readString(vmr, StandardCharsets.UTF_8);
            for (final String p : PARTITIONS) {
                if (s.contains("/" + p + "/")) {
                    return p;
                }
            }
        }
        for (final String p : PARTITIONS) {
            if (head(baseUrl + "/" + p + "/" + id + "/" + version + "/module.xml")) {
                return p;
            }
        }
        throw new IOException("Not in the official repository: " + id + " " + version);
    }

    private static boolean verified(final Path imm) throws IOException {
        final Path sha = Path.of(imm + ".sha1");
        if (!Files.isRegularFile(imm) || !Files.isRegularFile(sha)) {
            return false;
        }
        final String want = Files.readString(sha, StandardCharsets.US_ASCII).trim();
        return want.length() >= 40 && sha1(imm).equalsIgnoreCase(want.substring(0, 40));
    }

    static String sha1(final Path p) throws IOException {
        try (InputStream in = Files.newInputStream(p)) {
            final MessageDigest md = MessageDigest.getInstance("SHA-1");
            final byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0;) {
                md.update(buf, 0, n);
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (final java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private boolean head(final String url) throws IOException {
        IOException last = null;
        for (int i = 0; i < 4; i++) {
            try {
                final HttpResponse<Void> r = http.send(HttpRequest.newBuilder(URI.create(url)).method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.discarding());
                return r.statusCode() == 200;
            } catch (final IOException e) {
                last = e;
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            pause(i);
        }
        throw new IOException("HEAD " + url, last);
    }

    private static void pause(final int i) throws IOException {
        try {
            Thread.sleep(2000L * (i + 1));
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        }
    }

    private byte[] get(final String url, final boolean optional) throws IOException {
        IOException last = null;
        for (int i = 0; i < 4; i++) {
            try {
                final HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).build(),
                        HttpResponse.BodyHandlers.ofByteArray());
                if (r.statusCode() == 404 && optional) {
                    return null;
                }
                if (r.statusCode() != 200) {
                    throw new IOException("HTTP " + r.statusCode() + " " + url);
                }
                return r.body();
            } catch (final IOException e) {
                last = e;
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            pause(i);
        }
        throw new IOException("GET " + url, last);
    }

    private static void write(final Path p, final byte[] b) throws IOException {
        Files.createDirectories(p.getParent());
        final Path tmp = p.resolveSibling(p.getFileName() + ".part");
        Files.write(tmp, b);
        Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
