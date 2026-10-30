package io.github.jaxsh.intramart;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.file.Directory;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.api.provider.Provider;
import org.gradle.api.tasks.TaskProvider;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.bundling.Zip;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

/**
 * {@code plugins { id("im.accel-platform") }}：把本工程当作 intra-mart Accel Platform 上的一个应用。
 * <ul>
 * <li>src/main/java・resources → 本工程 jar → WAR 的 WEB-INF/lib；src/main/{conf,schema,plugin,jssp,storage,public,webapp} 按官方
 * モジュールプロジェクト的布局叠进 WAR（{@link ModuleLayout}）；</li>
 * <li>imWebapp：可直接部署的 WAR 目录；imWar：build/libs/imart.war；imModule：build/libs/&lt;name&gt;-&lt;version&gt;.imm（ユーザモジュール）；</li>
 * <li>imStart・imStop・imRestart・imRun・imDebug：本机 Resin。</li>
 * </ul>
 * 平台部分（模块解决、WAR 拼装）对用户不可见：解决结果与 WAR 目录在 build/intra-mart，imm・jar・BOM 缓存在 ~/.gradle/caches/intra-mart；
 * 本机 Resin 的运行目录在 ~/.intra-mart/resin/&lt;工程&gt;，storage 在 ~/.intra-mart/storage/&lt;库名&gt;。
 */
public class AccelPlatformPlugin implements Plugin<Project> {

    public static final String GROUP = "intra-mart";

    static final String MAVEN_BOM = "jp.co.intra_mart:im_bom:";

    /**
     * 编译类路径上要排除的传递依赖：官方 parent POM 的 exclusions（{@link MavenBom#exclusions}）之外，本插件补充的
     * （在 maven.intra-mart.jp 与 Maven Central 都取不到，parent 里却没列）。{group, module}，"*" 为整个 group。
     */
    private static final List<String[]> MAVEN_UNAVAILABLE_EXTRA = List.<String[]>of(new String[] {"org.apache.xalan", "*"});

    /** 字符串（相对路径按工程目录）、File、Directory → Directory。 */
    static Directory directory(final org.gradle.api.file.ProjectLayout layout, final Object dir) {
        if (dir instanceof Directory d) {
            return d;
        }
        return layout.getProjectDirectory().dir(dir instanceof File f ? f.getPath() : String.valueOf(dir));
    }

    /** jdbc:postgresql://host:5432/iap_db?x=y → iap_db（用作目录名，别的字符换成 _）。 */
    static String databaseName(final String jdbcUrl) {
        String s = jdbcUrl;
        final int q = s.indexOf('?');
        if (q >= 0) {
            s = s.substring(0, q);
        }
        s = s.substring(s.lastIndexOf('/') + 1);
        s = s.replaceAll("[^A-Za-z0-9_.-]", "_");
        return s.isEmpty() ? "default" : s;
    }

    private static final List<String> DEFAULT_APPLICATIONS = List.of(
            "module.pack.im_bpm", "module.pack.im_bloommaker_pack", "module.pack.z_forma", "module.pack.z_bis",
            "module.pack.im_spreadsheet_pack", "module.pack.im_bouquet_pack");

    @Override
    public void apply(final Project project) {
        project.getPluginManager().apply(JavaPlugin.class);
        final AccelPlatformExtension ext = project.getExtensions().create("intraMart", AccelPlatformExtension.class);
        final var layout = project.getLayout();
        final var providers = project.getProviders();

        ext.getEdition().convention("advanced");
        // 用 set 而不是 convention：这样 build 脚本里 applications.add("…") 是在缺省六个之上追加；要换掉就整个赋值 applications = […]
        ext.getApplications().set(DEFAULT_APPLICATIONS);
        ext.getRuntime().convention("resin");
        ext.getEnv().convention("ut");
        ext.getTrial().convention(true);
        // 本机设定（数据库・Resin 位置・storage 等）照 Gradle 的惯例取 Gradle 属性：-P、环境变量 ORG_GRADLE_PROJECT_…、
        // 工程的 gradle.properties、~/.gradle/gradle.properties，哪里有就用哪里；build.gradle 的 intraMart { } 里直接写也行
        final java.util.function.Function<String, Provider<String>> local = key ->
                providers.gradleProperty(key).filter(v -> !v.isEmpty());

        ext.getPatches().convention(local.apply("intraMart.patches").map(Boolean::valueOf).orElse(true));
        ext.getSamples().convention(false);
        ext.getLibraries().convention(local.apply("intraMart.libraries").orElse("maven"));
        ext.getAutoSetup().convention(local.apply("intraMart.autoSetup").map(Boolean::valueOf).orElse(true));
        ext.getRepositoryUrl().convention(local.apply("intraMart.repositoryUrl").orElse("http://repository.intra-mart.jp"));
        ext.getMavenRepositoryUrl().convention(local.apply("intraMart.mavenRepositoryUrl").orElse(MavenBom.DEFAULT_REPOSITORY));
        ext.getCacheDir().convention(layout.dir(providers.provider(() ->
                new File(project.getGradle().getGradleUserHomeDir(), "caches/intra-mart/repository"))));
        // Resin 的运行目录（conf・log・运行数据，类似 Tomcat 的 CATALINA_BASE）：不在工程里、不改 Resin 本体，按工程分：
        // ~/.intra-mart/resin/<工程名>-<工程路径的短哈希>（同名工程不撞）
        ext.getRuntimeDir().convention(layout.dir(providers.provider(() -> new File(System.getProperty("user.home"),
                ".intra-mart/resin/" + project.getName() + "-"
                        + Integer.toHexString(project.getProjectDir().getAbsolutePath().toLowerCase().hashCode())))));
        // storage 是租户的业务数据，与数据库配套，不放工程里：缺省 ~/.intra-mart/storage/<库名>（用同一个库的工程自然共用一份）
        final Provider<Directory> storageDir = providers.provider(ext::getStorageDir).map(o -> directory(layout, o))
                .orElse(layout.getProjectDirectory().dir(local.apply("intraMart.storageDir")))
                .orElse(layout.dir(ext.getDatasource().getUrl().map(url -> new File(System.getProperty("user.home"),
                        ".intra-mart/storage/" + databaseName(url)))));
        // 本机设置可以放 gradle.properties（工程的或 ~/.gradle/ 的），build.gradle.kts 里只留 version
        ext.getVersion().convention(local.apply("intraMart.version"));
        ext.getDatasource().getUrl().convention(local.apply("intraMart.datasource.url"));
        ext.getDatasource().getUser().convention(local.apply("intraMart.datasource.user").orElse("imart"));
        ext.getDatasource().getPassword().convention(local.apply("intraMart.datasource.password").orElse("imart"));
        ext.getDatasource().getMaxConnections().convention(20);
        // intraMart { resin { home = … } } 优先，没写再取 Gradle 属性 intraMart.resin.home
        final Provider<Directory> resinHome = providers.provider(() -> ext.getResin().getHome()).map(o -> directory(layout, o))
                .orElse(layout.getProjectDirectory().dir(local.apply("intraMart.resin.home")))
                // 都没写：自己找（RESIN_HOME、各盘根目录等），只在用到时找一次
                .orElse(layout.dir(providers.provider(new java.util.concurrent.Callable<File>() {
                    private Optional<File> found;

                    @Override
                    public synchronized File call() {
                        if (found == null) {
                            found = ResinLocator.find();
                            found.ifPresent(f -> project.getLogger().lifecycle(
                                    "Resin: {} (found automatically; set intraMart.resin.home to use another)", f));
                        }
                        return found.orElse(null);
                    }
                })));
        ext.getResin().getContextPath().convention(local.apply("intraMart.resin.contextPath").orElse("/imart"));
        ext.getResin().getStartTimeoutSeconds().convention(local.apply("intraMart.resin.startTimeoutSeconds").map(Integer::valueOf).orElse(900));
        ext.getResin().getDebugPort().convention(local.apply("intraMart.resin.debugPort").map(Integer::valueOf).orElse(5005));
        ext.getResin().getHttpPort().convention(local.apply("intraMart.resin.httpPort").map(Integer::valueOf).orElse(8080));
        ext.getResin().getJavaHome().convention(local.apply("intraMart.resin.javaHome").orElse(providers.systemProperty("java.home")));
        ext.getMirrors().from(local.apply("intraMart.mirrors").map(v -> List.of(v.split("[;,]"))).orElse(List.of()));
        // ユーザモジュール的元数据：照官方 Accel CLI 的缺省（module.xml 的 id ＝ groupId.artifactId）
        ext.getModule().getId().convention(providers.provider(() -> {
            final String g = String.valueOf(project.getGroup());
            return g.isEmpty() ? project.getName() : g + "." + project.getName();
        }));
        ext.getModule().getName().convention(project.getName());
        ext.getModule().getVendor().convention("");
        ext.getModule().getDescription().convention(providers.provider(() -> project.getDescription() == null ? "" : project.getDescription()));

        // 本工程的 Java：官方支持的 Resin 用 JDK 是 8・11・17・21，按 17 出字节码
        final JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.getToolchain().getLanguageVersion().convention(JavaLanguageVersion.of(21));
        project.getTasks().withType(JavaCompile.class).configureEach(t -> {
            t.getOptions().setEncoding("UTF-8");
            t.getOptions().getRelease().convention(17);
        });

        final Provider<Directory> work = layout.getBuildDirectory().dir("intra-mart");
        final Provider<Directory> runtime = ext.getRuntimeDir();
        // 解决结果（juggling.im・resolution.txt 等）是构建的中间产物：放 build/
        final Provider<Directory> setup = layout.getBuildDirectory().dir("intra-mart/setup");
        final Provider<org.gradle.api.file.RegularFile> classpathFile = setup.map(d -> d.file("compile-classpath.txt"));
        final Provider<org.gradle.api.file.RegularFile> bomFile = setup.map(d -> d.file("maven-bom.txt"));

        final TaskProvider<ImProjectTask> imProject = project.getTasks().register("imProject", ImProjectTask.class, t -> {
            t.setGroup(GROUP);
            t.setDescription("(part of imSetup) Resolves modules and module-order for intraMart.version, writes juggling.im and overlay files, fetches imm files into the cache");
            t.getBaseId().set(ext.getEdition().map(e -> PlatformResolver.PREFIX + "module.pack.im_" + e));
            t.getBaseVersion().set(ext.getVersion());
            t.getApplications().set(ext.getApplications());
            t.getApplicationVersions().set(ext.getApplicationVersions());
            t.getExcludes().set(ext.getExcludes());
            t.getIncludes().set(ext.getIncludes());
            t.getPatches().set(ext.getPatches());
            t.getPatchModules().set(ext.getPatchModules());
            t.getRepositoryUrl().set(ext.getRepositoryUrl());
            t.getCacheDir().set(ext.getCacheDir());
            t.getMirrors().from(ext.getMirrors());
            t.getProjectDir().set(setup.map(d -> d.dir("project")));
            t.getReport().set(setup.map(d -> d.file("resolution.txt")));
        });

        // 编译用的 intra-mart API：maven（缺省）选 BOM；platform 只取平台的 jar 放共享缓存（不在工程里展开平台）
        final TaskProvider<ImLibrariesTask> imLibraries = project.getTasks().register("imLibraries", ImLibrariesTask.class, t -> {
            t.setGroup(GROUP);
            t.setDescription("(part of imSetup) Prepares the intra-mart API for compiling: picks the official Maven BOM (libraries=maven, default) or caches the platform's WEB-INF/lib jars (libraries=platform)");
            t.getRepository().set(ext.getCacheDir());
            t.getCache().set(layout.dir(providers.provider(() ->
                    new File(project.getGradle().getGradleUserHomeDir(), "caches/intra-mart/jars"))));
            t.getJugglingProject().set(imProject.flatMap(ImProjectTask::getProjectDir));
            t.getRuntime().set(ext.getRuntime());
            t.getSamples().set(ext.getSamples());
            t.getClasspathFile().set(classpathFile);
            t.getMode().set(ext.getLibraries());
            t.getBomFile().set(bomFile);
            t.getMavenRepositoryUrl().set(ext.getMavenRepositoryUrl());
            t.getBaseVersion().set(ext.getVersion());
            t.getBomCache().set(layout.dir(providers.provider(() ->
                    new File(project.getGradle().getGradleUserHomeDir(), "caches/intra-mart/maven"))));
        });

        // ---- setup 与 build 分开：imSetup 准备平台并记下设定的指纹；build 只用准备好的平台，设定对不上就停 ----
        final Provider<String> fingerprint = providers.provider(() -> String.join("\n",
                "edition=" + ext.getEdition().get(),
                "version=" + ext.getVersion().getOrElse(""),
                "applications=" + ext.getApplications().get(),
                "applicationVersions=" + new java.util.TreeMap<>(ext.getApplicationVersions().get()),
                "excludes=" + new java.util.TreeSet<>(ext.getExcludes().get()),
                "includes=" + new java.util.TreeSet<>(ext.getIncludes().get()),
                "patches=" + ext.getPatches().get(),
                "patchModules=" + new java.util.TreeSet<>(ext.getPatchModules().get()),
                "runtime=" + ext.getRuntime().get(),
                "env=" + ext.getEnv().get(),
                "trial=" + ext.getTrial().get(),
                "samples=" + ext.getSamples().get(),
                "libraries=" + ext.getLibraries().get()));
        final Provider<org.gradle.api.file.RegularFile> stamp = setup.map(d -> d.file("setup.stamp"));
        project.getTasks().register("imSetup", SetupStampTask.class, t -> {
            t.setGroup(GROUP);
            t.setDescription("Prepares the intra-mart platform: resolve modules (with patches), fetch, cache the jars for compiling. Runs automatically before build when needed");
            t.dependsOn(imLibraries);
            t.getFingerprint().set(fingerprint);
            t.getStamp().set(stamp);
        });
        final TaskProvider<SetupCheckTask> imCheckSetup = project.getTasks().register("imCheckSetup", SetupCheckTask.class, t -> {
            t.setDescription("Checks that the prepared platform matches the current settings (used when intraMart.autoSetup=false)");
            t.getFingerprint().set(fingerprint);
            t.getStamp().set(stamp);
            t.mustRunAfter("imSetup");
        });

        // 编译类路径：共享缓存里平台的 WEB-INF/lib jar（imLibraries 写的清单；运行时由 WAR 自带，不打进本工程 jar）
        final org.gradle.api.file.FileCollection platformLibs = project.files((java.util.concurrent.Callable<List<File>>) () -> {
            final File f = classpathFile.get().getAsFile();
            if (!f.isFile()) {
                return List.of();
            }
            return Files.readAllLines(f.toPath()).stream().filter(l -> !l.isBlank()).map(File::new).toList();
        });
        project.getDependencies().add("compileOnly", platformLibs);
        // 测试也能用 intra-mart 的 API（只在测试类路径上，不进 WAR）
        project.getDependencies().add("testImplementation", platformLibs);
        // libraries=maven：官方 Maven 仓库＋imLibraries 选出的 BOM（模块由用户在 dependencies 里不带版本地声明）
        // BOM 版本要在配置阶段定（Gradle 建任务图时就解析编译依赖）：按 intraMart.version 选，pom 与目录有缓存
        final Provider<MavenBom> maven = providers.provider(() -> new MavenBom(
                new File(project.getGradle().getGradleUserHomeDir(), "caches/intra-mart/maven").toPath(), ext.getMavenRepositoryUrl().get()));
        final Provider<String> bomVersion = providers.provider(() -> "maven".equals(ext.getLibraries().get())
                ? maven.get().forBase(ext.getVersion().get())
                : null);
        for (final String conf : List.of("compileOnly", "testImplementation")) {
            project.getDependencies().addProvider(conf, bomVersion.map(v -> project.getDependencies().platform(MAVEN_BOM + v)));
        }
        project.afterEvaluate(p -> {
            if ("maven".equals(ext.getLibraries().get())) {
                // intra-mart 模块依赖的公开库（log4j 等）在 Central：工程一个仓库都没声明时补上 Central
                // （同官方 settings.xml：intra-mart＋central）。声明了的（如公司的代理仓库）照用户的
                final boolean noRepositories = p.getRepositories().isEmpty();
                p.getRepositories().maven(r -> {
                    r.setName("intra-mart");
                    r.setUrl(ext.getMavenRepositoryUrl().get());
                });
                if (noRepositories) {
                    p.getRepositories().mavenCentral();
                }
                // intra-mart 模块的 pom 引用了公开仓库没有的构件，编译用不到：按官方 parent POM 的 exclusions 排除，再加插件补充的
                final List<String[]> excluded = new java.util.ArrayList<>(MAVEN_UNAVAILABLE_EXTRA);
                try {
                    excluded.addAll(maven.get().exclusions(bomVersion.get()));
                } catch (final java.io.IOException e) {
                    throw new org.gradle.api.GradleException("Cannot read the intra-mart parent POM: " + e.getMessage(), e);
                }
                p.getConfigurations().configureEach(c -> excluded.forEach(x ->
                        c.exclude("*".equals(x[1]) ? Map.of("group", x[0]) : Map.of("group", x[0], "module", x[1]))));
            }
        });
        // 平台的前置：缺省自动跑 imSetup（设定没变就 UP-TO-DATE）；autoSetup=false 时只检查
        final java.util.concurrent.Callable<String> platformReady = () -> ext.getAutoSetup().get() ? "imSetup" : "imCheckSetup";
        project.getTasks().named(JavaPlugin.COMPILE_JAVA_TASK_NAME, t -> t.dependsOn(platformReady));

        final TaskProvider<Jar> jar = project.getTasks().named(JavaPlugin.JAR_TASK_NAME, Jar.class);
        final Provider<Directory> webappDir = work.map(d -> d.dir("webapp"));
        final TaskProvider<WebappTask> imWebapp = project.getTasks().register("imWebapp", WebappTask.class, t -> {
            t.setGroup(GROUP);
            t.setDescription("Builds the deployable exploded WAR in build/intra-mart/webapp: expands the platform from the imm files when it changed, then adds this project's jar, dependencies and src/main/webapp");
            t.dependsOn(platformReady);
            t.getRepository().set(ext.getCacheDir());
            t.getJugglingProject().set(setup.map(d -> d.dir("project")));
            t.getRuntime().set(ext.getRuntime());
            t.getEnv().set(ext.getEnv());
            t.getSamples().set(ext.getSamples());
            t.getTrial().set(ext.getTrial());
            // dependencies { implementation(...) / runtimeOnly(...) } 的库也进 WEB-INF/lib（compileOnly 的平台 jar 不在 runtimeClasspath 里）
            t.getLibraries().from(jar, project.getConfigurations().named(JavaPlugin.RUNTIME_CLASSPATH_CONFIGURATION_NAME));
            t.getSourceRoot().set(layout.getProjectDirectory().dir("src/main"));
            t.getDestination().set(webappDir);
            t.getStateDir().set(work.map(d -> d.dir("webapp-state")));
        });

        // 同一份源码也能打成ユーザモジュール（.imm）：给 IM-Juggling 导入、Docker Stacks 的 additional-modules、Accel CLI 的 deploy 用
        final TaskProvider<ImModuleTask> imModule = project.getTasks().register("imModule", ImModuleTask.class, t -> {
            t.setGroup(GROUP);
            t.setDescription("Packages this project as an intra-mart user module (build/libs/<name>-<version>.imm): module.xml, the jar, and src/main/{conf,schema,plugin,jssp,storage,public,webapp}");
            t.getModuleId().set(ext.getModule().getId());
            t.getArtifactId().set(project.getName());
            t.getVersion().set(providers.provider(() -> String.valueOf(project.getVersion())));
            t.getModuleName().set(ext.getModule().getName());
            t.getVendor().set(ext.getModule().getVendor());
            t.getModuleDescription().set(ext.getModule().getDescription());
            t.getJar().set(jar.flatMap(Jar::getArchiveFile));
            t.getSourceRoot().set(layout.getProjectDirectory().dir("src/main"));
            t.getOutput().set(layout.getBuildDirectory().file(providers.provider(() -> "libs/" + project.getName() + "-" + project.getVersion() + ".imm")));
        });

        final TaskProvider<Zip> imWar = project.getTasks().register("imWar", Zip.class, t -> {
            t.setGroup(GROUP);
            t.setDescription("Packages build/libs/imart.war");
            t.dependsOn(imWebapp);
            t.from(webappDir, s -> s.exclude("WEB-INF/log/**", "WEB-INF/work/**", "WEB-INF/tmp/**", "WEB-INF/report/**"));
            // 只给缺省值：WAR 名 ＝ 部署时的上下文路径（imart.war → /imart）。改名照 Gradle 的写法：
            // tasks.named('imWar') { archiveBaseName = 'foo' } 或 archiveFileName = 'foo.war'、destinationDirectory = …
            t.getArchiveBaseName().convention("imart");
            t.getArchiveVersion().convention("");
            // Zip 任务自己把扩展名设成 zip（不是缺省值），convention 压不过它，这里要 set；用户在 named('imWar') 里再设照样能改
            t.getArchiveExtension().set("war");
            t.getDestinationDirectory().convention(layout.getBuildDirectory().dir("libs"));
            t.setZip64(true);
        });
        project.getTasks().named("assemble", t -> t.dependsOn(imWar, imModule));

        final TaskProvider<ResinConfigTask> imResinConfig = project.getTasks().register("imResinConfig", ResinConfigTask.class, t -> {
            t.setGroup(GROUP);
            t.setDescription("Generates this project's local Resin configuration from resin.home/conf (~/.intra-mart/resin/<project>/conf)");
            t.getResinHome().set(resinHome);
            t.getResinHomePath().set(resinHome.map(d -> d.getAsFile().getAbsolutePath()));
            t.getWebappDir().set(webappDir.map(d -> d.getAsFile().getAbsolutePath()));
            t.getHttpPort().set(ext.getResin().getHttpPort());
            t.getContextPath().set(ext.getResin().getContextPath());
            t.getJvmArgs().set(ext.getResin().getJvmArgs());
            t.getDatasourceUrl().set(ext.getDatasource().getUrl());
            t.getDatasourceUser().set(ext.getDatasource().getUser());
            t.getDatasourcePassword().set(ext.getDatasource().getPassword());
            t.getDatasourceMaxConnections().set(ext.getDatasource().getMaxConnections());
            t.getStorageDir().set(storageDir.map(d -> d.getAsFile().getAbsolutePath()));
            t.getConfDir().set(runtime.map(d -> d.dir("conf")));
        });

        // 本机设定缺了就在一开头停下（不等准备平台・拼 WAR 目录跑完）
        final TaskProvider<LocalCheckTask> imCheckLocal = project.getTasks().register("imCheckLocal", LocalCheckTask.class, t -> {
            t.setDescription("Checks the local settings for running Resin (resin.home, datasource, JDK) before anything slow runs");
            t.getResinHome().set(resinHome);
            t.getDatasourceUrl().set(ext.getDatasource().getUrl());
            t.getJavaHome().set(ext.getResin().getJavaHome());
            t.getStorageDir().set(storageDir);
        });
        for (final String name : List.of("imProject", "imWebapp", "imResinConfig", JavaPlugin.COMPILE_JAVA_TASK_NAME)) {
            project.getTasks().named(name, t -> t.mustRunAfter(imCheckLocal));
        }

        for (final String[] c : new String[][] {
                // {名, 命令, follow（前台跟日志、取消时正常停）, debug, 说明}
                {"imStart", "start", "", "", "Builds the exploded WAR and starts Resin in the background, waits until the web app is active"},
                {"imStop", "stop", "", "", "Stops Resin"},
                // restart 先停、再同步 WAR 目录、再起：Resin 开着时 WEB-INF 下的 jar・tmp 被占用，Windows 上删不掉（官方手顺也是先アンデプロイ）
                {"imRestart", "start", "", "", "Stops Resin, rebuilds the exploded WAR, starts Resin (after code changes, upgrades, patches)"},
                {"imRun", "start", "follow", "", "Builds the exploded WAR and runs Resin, showing its log; stopping the task stops Resin cleanly"},
                {"imDebug", "start", "follow", "debug", "Like imRun, with the JVM debug port open (intraMart.resin.debugPort, default 5005) for Remote JVM Debug"}}) {
            project.getTasks().register(c[0], ResinTask.class, t -> {
                t.setGroup(GROUP);
                t.setDescription(c[4]);
                t.getCommand().set(c[1]);
                t.getFollow().set(!c[2].isEmpty());
                if (!c[3].isEmpty()) {
                    t.getDebugPort().set(ext.getResin().getDebugPort());
                }
                t.getResinHome().set(resinHome);
                t.getJavaHome().set(ext.getResin().getJavaHome());
                t.getConfDir().set(imResinConfig.flatMap(ResinConfigTask::getConfDir));
                t.getRootDir().set(runtime);
                t.getStartTimeoutSeconds().set(ext.getResin().getStartTimeoutSeconds());
                t.getContextPath().set(ext.getResin().getContextPath());
                t.getHttpPort().set(ext.getResin().getHttpPort());
                if (!"stop".equals(c[1])) {
                    t.dependsOn(imCheckLocal, imWebapp, imResinConfig);
                }
            });
        }
        project.getTasks().named("imRestart", t -> t.dependsOn("imStop"));
        imWebapp.configure(t -> t.mustRunAfter("imStop"));
        imResinConfig.configure(t -> t.mustRunAfter("imStop"));
    }
}
