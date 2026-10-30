package io.github.jaxsh.intramart;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * 从 resin.home/conf 生成本工程用的 Resin 配置（resin.home 只读，不改）：
 * <ul>
 * <li>resin.xml：${__DIR__} 换成绝对路径（IDE 的 Resin 集成会把 resin.xml 复制到别处启动），缺省 web-app 换成上下文路径（缺省 /imart）→ 本工程的 WAR 目录；</li>
 * <li>resin.xml 的 &lt;cluster id="app"&gt; 下定义 jdbc/default：本机的数据源只在这里（WAR 里不带；内容同 Juggling 样例工程 resin-web.xml 里的 database 段）；</li>
 * <li>cluster-default.xml・health.xml：webapp-jars 与 doc 指 resin.home（resin.root 改在工程里了）；</li>
 * <li>resin.properties：HTTP 端口、-Dim.storage.dir（WAR 的 storage-config.xml 写的是 ${im.storage.dir}）、追加 JVM 参数、imart_webapp。</li>
 * </ul>
 * 这是「本机部署」：测试・正式环境由各自的 Resin 管理员做同样的事，WAR 用同一个。
 */
@DisableCachingByDefault(because = "machine-specific local configuration")
public abstract class ResinConfigTask extends DefaultTask {

    @Internal
    public abstract DirectoryProperty getResinHome();

    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getResinHomePath();

    @Input
    public abstract Property<String> getWebappDir();

    @Input
    public abstract Property<Integer> getHttpPort();

    @Input
    public abstract Property<String> getContextPath();

    @Input
    public abstract ListProperty<String> getJvmArgs();

    @Input
    @org.gradle.api.tasks.Optional
    public abstract Property<String> getDatasourceUrl();

    @Input
    public abstract Property<String> getDatasourceUser();

    @Input
    public abstract Property<String> getDatasourcePassword();

    @Input
    public abstract Property<Integer> getDatasourceMaxConnections();

    /** storage 的根（绝对路径）→ -Dim.storage.dir。 */
    @Input
    public abstract Property<String> getStorageDir();

    @OutputDirectory
    public abstract DirectoryProperty getConfDir();

    @TaskAction
    public void generate() throws IOException {
        if (!getResinHome().isPresent()) {
            throw new org.gradle.api.GradleException("intraMart.resin.home is not set");
        }
        final Path home = getResinHome().get().getAsFile().toPath();
        final Path src = home.resolve("conf");
        final Path dst = getConfDir().get().getAsFile().toPath();
        Files.createDirectories(dst);
        final String confAbs = dst.toAbsolutePath().toString().replace('\\', '/');

        String resinXml = read(src.resolve("resin.xml")).replace("${__DIR__}", confAbs);
        final Matcher root = Pattern.compile("<web-app\\s+id=\"/\"\\s+root-directory=\"webapps/ROOT\"\\s*/>").matcher(resinXml);
        if (!root.find()) {
            throw new IOException(src.resolve("resin.xml") + " has no default <web-app id=\"/\" root-directory=\"webapps/ROOT\"/> to replace with the intra-mart web app");
        }
        resinXml = resinXml.substring(0, root.start()) + "<web-app id=\"" + getContextPath().get() + "\" root-directory=\"${imart_webapp}\"/>"
                + resinXml.substring(root.end());
        final Matcher cluster = Pattern.compile("<cluster\\s+id=\"app\"\\s*>").matcher(resinXml);
        if (!cluster.find()) {
            throw new IOException(src.resolve("resin.xml") + " has no <cluster id=\"app\"> to put jdbc/default in");
        }
        resinXml = resinXml.substring(0, cluster.end()) + datasourceXml() + resinXml.substring(cluster.end());
        write(dst.resolve("resin.xml"), resinXml);

        for (final String f : List.of("cluster-default.xml", "health.xml", "app-default.xml")) {
            write(dst.resolve(f), read(src.resolve(f)).replace("${__DIR__}", confAbs)
                    .replace("${resin.root}/webapp-jars", "${resin.home}/webapp-jars")
                    .replace("${resin.root}/doc/", "${resin.home}/doc/"));
        }

        String props = read(src.resolve("resin.properties"));
        props = setProp(props, "app.http", String.valueOf(getHttpPort().get()));
        props = setProp(props, "web.http", String.valueOf(getHttpPort().get()));
        final List<String> extraArgs = new java.util.ArrayList<>();
        final String storage = getStorageDir().get().replace('\\', '/');
        if (storage.contains(" ")) {
            throw new IOException("Storage path must not contain spaces (resin.properties jvm_args is space-separated): " + storage);
        }
        extraArgs.add("-D" + ImProjectTask.STORAGE_PROPERTY + "=" + storage);
        extraArgs.addAll(getJvmArgs().get());
        final Matcher jm = Pattern.compile("(?m)^jvm_args\\s*:(.*)$").matcher(props);
        final String extra = String.join(" ", extraArgs);
        props = jm.find() ? props.substring(0, jm.end()) + " " + extra + props.substring(jm.end()) : props + "\njvm_args : " + extra + "\n";
        props += "\n# ---- intra-mart-gradle ----\nimart_webapp : " + getWebappDir().get().replace('\\', '/') + "\n";
        write(dst.resolve("resin.properties"), props);

        // IDE 的 Resin 集成不经 watchdog 直接起 Resin，resin.properties 的 jvm_args 不生效：给一份照抄用。
        // 它的工作目录是 resin.home，相对的 -Djava.io.tmpdir=tmp 会写进 resin.home：换成本工程 resin 根下的 tmp
        final Matcher jvm = Pattern.compile("(?m)^jvm_args\\s*:(.*)$").matcher(props);
        final String tmp = dst.getParent().resolve("tmp").toAbsolutePath().toString().replace('\\', '/');
        final String vm = (jvm.find() ? jvm.group(1).trim() : "").replaceAll("-Djava\\.io\\.tmpdir=\\S+",
                Matcher.quoteReplacement("-Djava.io.tmpdir=" + tmp));
        write(dst.resolve("resin-vm-options.txt"), vm + "\n");
    }

    /** jdbc/default（内容同 Juggling 样例工程 resin-web.xml 里的那段：PostgreSQL、prepared statement cache 0）。 */
    private String datasourceXml() {
        if (!getDatasourceUrl().isPresent() || getDatasourceUrl().get().isBlank()) {
            throw new org.gradle.api.GradleException("intraMart.datasource.url is not set");
        }
        return """

                    <!-- intra-mart-gradle：本机的数据源（WAR 里不带） -->
                    <database jndi-name="jdbc/default">
                      <driver>
                        <type>org.postgresql.ds.PGConnectionPoolDataSource</type>
                        <url>%s</url>
                        <user>%s</user>
                        <password>%s</password>
                        <preparedStatementCacheQueries>0</preparedStatementCacheQueries>
                      </driver>
                      <max-connections>%d</max-connections>
                      <prepared-statement-cache-size>0</prepared-statement-cache-size>
                    </database>
                """.formatted(xml(getDatasourceUrl().get()), xml(getDatasourceUser().get()), xml(getDatasourcePassword().get()),
                getDatasourceMaxConnections().get());
    }

    private static String xml(final String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String setProp(final String props, final String key, final String value) {
        final Matcher m = Pattern.compile("(?m)^" + Pattern.quote(key) + "\\s*:.*$").matcher(props);
        return m.find() ? props.substring(0, m.start()) + key + " : " + value + props.substring(m.end())
                : props + "\n" + key + " : " + value + "\n";
    }

    private static String read(final Path p) throws IOException {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static void write(final Path p, final String s) throws IOException {
        Files.writeString(p, s, StandardCharsets.UTF_8);
    }
}
