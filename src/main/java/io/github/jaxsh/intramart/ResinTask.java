package io.github.jaxsh.intramart;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.gradle.api.DefaultTask;
import org.gradle.api.GradleException;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * 起停本工程的 Resin：java -jar &lt;resin.home&gt;/lib/resin.jar -conf … -root-directory … start|stop。都经 watchdog。
 * <ul>
 * <li>start：后台起，等到 /imart active（或 fail）就结束；</li>
 * <li>follow（imRun・imDebug）：同样后台起，但把 jvm-app-0.log 一直打到 Gradle 的输出里，像 bootRun 一样前台跑；
 * 任务被取消（IDE 的停止按钮・Ctrl-C）时用 stop 正常停掉。不强制结束进程：强制结束时 intra-mart 不做关闭处理，
 * imsec_login_session 会留下登录行，下次同一用户登不进（经 watchdog 的 stop 会清掉）；</li>
 * <li>不用 Resin 的 console 模式：它不经 watchdog、不写 jvm-app-0.log，resin stop 也停不了它；</li>
 * <li>debugPort：Resin 的 --debug-port，应用服务器的 JVM 开 JDWP，IDE 用 Remote JVM Debug 连。</li>
 * </ul>
 */
@DisableCachingByDefault(because = "starts and stops a process")
public abstract class ResinTask extends DefaultTask {

    @Input
    public abstract Property<String> getCommand();

    /** 前台跟着日志跑，取消时正常停（imRun・imDebug）。 */
    @Input
    public abstract Property<Boolean> getFollow();

    @Input
    @Optional
    public abstract Property<Integer> getDebugPort();

    @Internal
    public abstract DirectoryProperty getResinHome();

    @Input
    public abstract Property<String> getJavaHome();

    @Internal
    public abstract DirectoryProperty getConfDir();

    @Internal
    public abstract DirectoryProperty getRootDir();

    /** start 时等 /imart 起来的上限（秒）。 */
    @Input
    public abstract Property<Integer> getStartTimeoutSeconds();

    /** 等哪个 web-app 起来（Resin 日志 WebApp[…/<path>] active）。 */
    @Input
    public abstract Property<String> getContextPath();

    /** 起来后打的 URL、follow 时看 Resin 还在不在。 */
    @Input
    public abstract Property<Integer> getHttpPort();

    public ResinTask() {
        getOutputs().upToDateWhen(t -> false);
        getFollow().convention(false);
    }

    @TaskAction
    public void run() throws IOException, InterruptedException {
        final Path root = getRootDir().get().getAsFile().toPath();
        Files.createDirectories(root.resolve("log"));
        Files.createDirectories(root.resolve("tmp"));
        Files.createDirectories(root.resolve("webapps"));
        if ("stop".equals(getCommand().get())) {
            final int rc = resin(List.of("stop"));
            if (rc != 0) {
                getLogger().lifecycle("Resin is not running (stop exit {})", rc);
            }
            return;
        }

        final Path appLog = root.resolve("log/jvm-app-0.log");
        final LogTail tail = new LogTail(appLog);
        final boolean follow = getFollow().get();
        final List<String> args = new ArrayList<>(List.of("start"));
        if (getDebugPort().isPresent()) {
            args.add("--debug-port");
            args.add(String.valueOf(getDebugPort().get()));
        }
        final int rc = resin(args);
        if (rc != 0) {
            throw new GradleException("resin start failed (exit " + rc + "); is it already running? (./gradlew imStop)");
        }
        try {
            waitForWebapp(tail, follow);
            logUp(appLog);
            if (getDebugPort().isPresent()) {
                getLogger().lifecycle("Debugger: attach to localhost:{} (remote JVM debug / JDWP attach from your IDE)",
                        getDebugPort().get());
            }
            if (follow) {
                getLogger().lifecycle("Resin keeps running; stop this task (IDE stop button or Ctrl-C) to stop Resin");
                followUntilStopped(tail);
            }
        } catch (final InterruptedException | RuntimeException e) {
            if (follow) {
                stopGracefully();
            }
            throw e;
        }
    }

    /** 跑 resin.jar 的一个命令（start・stop 都很快返回：start 只是让 watchdog 起服务器），输出进 Gradle 的日志。 */
    private int resin(final List<String> args) throws IOException, InterruptedException {
        final Path root = getRootDir().get().getAsFile().toPath();
        final String javaHome = getJavaHome().get();
        final List<String> cmd = new ArrayList<>(List.of(
                Path.of(javaHome, "bin", "java").toString(), "-jar",
                getResinHome().get().file("lib/resin.jar").getAsFile().getAbsolutePath(),
                "-conf", getConfDir().get().file("resin.xml").getAsFile().getAbsolutePath(),
                "-root-directory", root.toAbsolutePath().toString(),
                "-log-directory", root.resolve("log").toAbsolutePath().toString()));
        cmd.addAll(args);
        // 输出转进 Gradle 的日志：inheritIO 的话进的是 daemon 的 stdout，IDE・终端里都看不到
        final ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().put("JAVA_HOME", javaHome);
        getLogger().lifecycle("Resin {} (JDK {})", String.join(" ", args), javaHome);
        final Process p = pb.start();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            for (String line; (line = r.readLine()) != null;) {
                getLogger().lifecycle("{}", line);
            }
        }
        return p.waitFor();
    }

    /** 取消后：清掉中断标志再跑 stop，等它做完（intra-mart 的关闭处理会清登录会话等）。 */
    private void stopGracefully() {
        Thread.interrupted();
        getLogger().lifecycle("Stopping Resin");
        try {
            resin(List.of("stop"));
        } catch (final IOException | InterruptedException e) {
            getLogger().error("Could not stop Resin ({}); run ./gradlew imStop", e.toString());
        }
    }

    /** 一直打日志，直到被取消，或 Resin 被别处停了（HTTP 端口连续 15 秒连不上）。 */
    private void followUntilStopped(final LogTail tail) throws IOException, InterruptedException {
        int down = 0;
        for (int tick = 1;; tick++) {
            tail.lines().forEach(l -> getLogger().lifecycle("{}", l));
            if (tick % 5 == 0) {
                down = listening(getHttpPort().get()) ? 0 : down + 1;
                if (down >= 3) {
                    tail.lines().forEach(l -> getLogger().lifecycle("{}", l));
                    getLogger().lifecycle("Resin has stopped");
                    return;
                }
            }
            Thread.sleep(1000);
        }
    }

    private static boolean listening(final int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("localhost", port), 1000);
            return true;
        } catch (final IOException e) {
            return false;
        }
    }

    private void logUp(final Path appLog) {
        getLogger().lifecycle("intra-mart is up: http://localhost:{}{}/login  (log {})", getHttpPort().get(),
                getContextPath().get(), appLog);
    }

    private void waitForWebapp(final LogTail tail, final boolean print) throws IOException, InterruptedException {
        final String cp = getContextPath().get();
        final long deadline = System.currentTimeMillis() + getStartTimeoutSeconds().get() * 1000L;
        int ticks = 0;
        while (System.currentTimeMillis() < deadline) {
            for (final String l : tail.lines()) {
                if (print) {
                    getLogger().lifecycle("{}", l);
                }
                if (l.contains(cp + "] active")) {
                    return;
                }
                if (l.contains(cp + "] fail")) {
                    throw new GradleException(cp + " failed to start; see " + tail.file + " and WEB-INF/log/platform/exception/ in the exploded WAR");
                }
            }
            if (!print && ++ticks % 30 == 0) {
                getLogger().lifecycle("Waiting for {} to become active ({} s, log {})", cp, ticks, tail.file);
            }
            Thread.sleep(1000);
        }
        throw new GradleException(cp + " did not become active within " + getStartTimeoutSeconds().get() + " seconds; see " + tail.file);
    }

    /** 从起动时的末尾开始读 jvm-app-0.log 新增的整行。 */
    private static final class LogTail {
        final Path file;
        private long pos;

        LogTail(final Path file) throws IOException {
            this.file = file;
            this.pos = Files.exists(file) ? Files.size(file) : 0;
        }

        List<String> lines() throws IOException {
            final List<String> out = new ArrayList<>();
            if (!Files.exists(file)) {
                return out;
            }
            final long size = Files.size(file);
            if (size < pos) {
                pos = 0; // 日志被换了
            }
            if (size > pos) {
                final byte[] b;
                try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
                    f.seek(pos);
                    b = new byte[(int) Math.min(size - pos, 1 << 24)];
                    f.readFully(b);
                }
                // 只读到最后一个换行：半行（以及被切开的多字节字符）留到下次
                int end = b.length;
                while (end > 0 && b[end - 1] != '\n') {
                    end--;
                }
                pos += end;
                for (final String l : new String(b, 0, end, StandardCharsets.UTF_8).split("\n")) {
                    if (end > 0) {
                        out.add(l.replace("\r", ""));
                    }
                }
            }
            return out;
        }
    }
}
