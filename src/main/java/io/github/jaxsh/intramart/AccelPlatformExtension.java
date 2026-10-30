package io.github.jaxsh.intramart;

import java.util.Arrays;

import org.gradle.api.Action;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.provider.SetProperty;
import org.gradle.api.tasks.Nested;

/**
 * build.gradle.kts 里的 {@code intraMart { … }}。常用的只有 version；其余都有缺省。
 *
 * <pre>
 * intraMart {
 *     version = "8.0.40"                       // base pack（im_advanced）的版本：升级只改这里
 *     datasource { url = "jdbc:postgresql://localhost:5432/iap_db"; user = "imart"; password = "imart" }
 *     resin { home = file("/opt/resin-pro-4.0.67") }
 * }
 * </pre>
 */
public abstract class AccelPlatformExtension {

    /** base pack 的版本，如 8.0.40（2026 Autumn）。 */
    public abstract Property<String> getVersion();

    /** advanced（缺省）・professional 等：base pack ＝ module.pack.im_&lt;edition&gt;。 */
    public abstract Property<String> getEdition();

    /** 一起装的应用 pack（模块 ID，可省 jp.co.intra_mart. 前缀）。缺省：BPM・BloomMaker・Forma・BIS・Spreadsheet・Accel Studio。 */
    public abstract ListProperty<String> getApplications();

    /** 应用 pack 钉版本；不钉的取能配该 base 版本的最高版本。 */
    public abstract MapProperty<String, String> getApplicationVersions();

    /** 不选的模块（在缺省不选之外再加）。 */
    public abstract SetProperty<String> getExcludes();

    /** 从缺省不选里拿回来的模块。 */
    public abstract SetProperty<String> getIncludes();

    /**
     * 编译・测试用的 intra-mart API 从哪来：maven（缺省：官方 Maven 仓库 https://maven.intra-mart.jp/ 的 BOM，
     * 自动选与 version 一致的那版；在 dependencies 里不带版本地写要用的模块，如 compileOnly 'jp.co.intra_mart:im_logic'）・
     * platform（取自平台的 WEB-INF/lib，531 个 jar 全在类路径上，不用写依赖；离线可用）。
     */
    public abstract Property<String> getLibraries();

    /** 打补丁（缺省 true：各模块换成同版本号的最新 -PATCH_n，同 IM-Juggling「全てのパッチを適用する」）。最新指上次 imProject --refresh 时的。 */
    public abstract Property<Boolean> getPatches();

    /** 只给这些模块打补丁（不空时；官方「個別に適用するパッチを選択する」，非推奨）。 */
    public abstract SetProperty<String> getPatchModules();

    /** resin（缺省）。 */
    public abstract Property<String> getRuntime();

    /** ut（缺省）・si・pt・production：WEB-INF/classes/im_env.txt。 */
    public abstract Property<String> getEnv();

    /** 试用版（缺省 true：拼装时删 highcharts，同官方 CUI 拼装的缺省）。 */
    public abstract Property<Boolean> getTrial();

    /** 展开 sample 构件（缺省 false）。 */
    public abstract Property<Boolean> getSamples();

    /**
     * build・imWebapp・imStart 前自动跑 imSetup（缺省 true；设定没变时 UP-TO-DATE，不重做）。
     * false：不自动准备，只检查；平台没准备或设定变了就报错（如不想让 CI 在构建时下载模块）。
     */
    public abstract Property<Boolean> getAutoSetup();

    /** imm 模块仓库（缺省官方 http://repository.intra-mart.jp；Gradle 属性 intraMart.repositoryUrl）。 */
    public abstract Property<String> getRepositoryUrl();

    /** Maven 仓库（libraries=maven 时；缺省官方 https://maven.intra-mart.jp/；Gradle 属性 intraMart.mavenRepositoryUrl，可指公司的代理仓库）。 */
    public abstract Property<String> getMavenRepositoryUrl();

    /** 模块缓存（缺省 ~/.gradle/caches/intra-mart/repository，Juggling 本地仓库布局，多工程共用）。 */
    public abstract DirectoryProperty getCacheDir();

    /** 只读镜像（Juggling 本地仓库布局，如 ~/juggling/workspace/.repository）：缓存没有时先从这里复制。 */
    public abstract ConfigurableFileCollection getMirrors();

    /** 本机 Resin 的运行目录（配置・日志・Resin 的运行数据；缺省 ~/.intra-mart/resin/&lt;工程名&gt;-&lt;哈希&gt;，不在工程里）。 */
    public abstract DirectoryProperty getRuntimeDir();

    /**
     * storage 的根：租户的业务数据（テナント環境セットアップ与业务会往这里写文件），与数据库配套，不放工程里。
     * 缺省 ~/.intra-mart/storage/&lt;库名&gt;（库名取自 datasource.url）：用同一个库的工程自动共用；要放别处就设 intraMart.storageDir。
     */
    public Object getStorageDir() {
        return storageDir;
    }

    /** 字符串（相对路径按工程目录）或 file(…) 都行。不是 DirectoryProperty：那样 Groovy 里赋字符串会报类型错。 */
    public void setStorageDir(final Object dir) {
        this.storageDir = dir;
    }

    private Object storageDir;

    @Nested
    public abstract Datasource getDatasource();

    @Nested
    public abstract Resin getResin();

    /** imModule 打出的ユーザモジュール的 module.xml 内容。 */
    @Nested
    public abstract Module getModule();

    public void module(final Action<? super Module> a) {
        a.execute(getModule());
    }

    public void datasource(final Action<? super Datasource> a) {
        a.execute(getDatasource());
    }

    public void resin(final Action<? super Resin> a) {
        a.execute(getResin());
    }

    public void exclude(final String... ids) {
        getExcludes().addAll(Arrays.asList(ids));
    }

    public void include(final String... ids) {
        getIncludes().addAll(Arrays.asList(ids));
    }

    /** 只给这些模块打补丁。 */
    public void patch(final String... ids) {
        getPatchModules().addAll(Arrays.asList(ids));
    }

    /** resin-web.xml 的 jdbc/default（PostgreSQL）。 */
    public abstract static class Datasource {
        public abstract Property<String> getUrl();

        public abstract Property<String> getUser();

        public abstract Property<String> getPassword();

        public abstract Property<Integer> getMaxConnections();
    }

    /** ユーザモジュール的元数据（都有缺省：id＝group.name，name＝工程名，description＝工程 description）。版本用工程的 version。 */
    public abstract static class Module {
        public abstract Property<String> getId();

        public abstract Property<String> getName();

        public abstract Property<String> getVendor();

        public abstract Property<String> getDescription();
    }

    /** 本机 Resin（resin.home 只读引用，不写；配置・日志・运行数据都在 runtimeDir 下）。 */
    public abstract static class Resin {
        private Object home;

        /** Resin 的位置：字符串或 file(…) 都行。 */
        public Object getHome() {
            return home;
        }

        public void setHome(final Object dir) {
            this.home = dir;
        }

        /** 起 Resin 的 JDK（缺省：跑 Gradle 的 JDK）。 */
        public abstract Property<String> getJavaHome();

        public abstract Property<Integer> getHttpPort();

        /** imDebug 开的 JVM 调试端口（Resin 的 --debug-port，缺省 5005）。 */
        public abstract Property<Integer> getDebugPort();

        /** 追加的 JVM 参数（resin.properties 的 jvm_args 之后）。 */
        public abstract ListProperty<String> getJvmArgs();

        /** 本机 Resin 上的上下文路径（缺省 /imart）。打的 WAR 名另由 imWar 任务定。 */
        public abstract Property<String> getContextPath();

        /** imStart 等 /imart 起来的上限（秒，缺省 900）。 */
        public abstract Property<Integer> getStartTimeoutSeconds();
    }
}
