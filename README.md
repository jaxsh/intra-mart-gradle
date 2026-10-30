# intra-mart-gradle

Gradle 插件 `io.github.jaxsh.intra-mart`：用 Gradle 替 IM-Juggling——按版本解决 intra-mart Accel Platform 的模块，拼 WAR，在本机 Resin 上跑。普通的 Gradle 工程，IDE 不限。

## 用法（应用工程）

```kotlin
// build.gradle.kts
plugins { id("io.github.jaxsh.intra-mart") version "0.1.0" }
intraMart { version = "8.0.40" } // 升级只改这里
```

插件发在本仓的 `maven` 分支（Maven 布局，匿名可读），settings 里加一行仓库即可：

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        maven("https://raw.githubusercontent.com/jaxsh/intra-mart-gradle/maven/")
        gradlePluginPortal()
    }
}
```

改插件源码时也可以直接引用本仓：`pluginManagement { includeBuild("../intra-mart-gradle") }`，`plugins { id("io.github.jaxsh.intra-mart") }` 不写版本。

```properties
# gradle.properties（或 ~/.gradle/gradle.properties、-P、环境变量 ORG_GRADLE_PROJECT_…）
# resin.home 不写时自动找：RESIN_HOME → 各盘根目录・Program Files・用户目录下的 resin*（取版本最高的）
# 放在别处就在 ~/.gradle/gradle.properties 写一次，所有工程共用
intraMart.datasource.url=jdbc:postgresql://localhost:5432/iap_db
intraMart.resin.home=/opt/resin-pro-4.0.67
# 仓库地址（缺省官方；公司有代理仓库时改）
# intraMart.repositoryUrl=http://repository.intra-mart.jp
# intraMart.mavenRepositoryUrl=https://maven.intra-mart.jp/
# 要经代理上网时（Gradle 与插件都读）
# systemProp.https.proxyHost=proxy.example.com
# systemProp.https.proxyPort=8080
# systemProp.http.proxyHost=proxy.example.com
# systemProp.http.proxyPort=8080
```

| 命令 | 做什么 |
|---|---|
| `./gradlew imSetup` | 准备平台：解决模块（含补丁）→ 取模块 → 编译用 jar 放进共享缓存。`build`・`imStart` 前会自动跑（设定没变就 UP-TO-DATE），一般不用手动 |
| `./gradlew imStart` | 拼 WAR 目录（平台＋本工程 jar）→ 生成 Resin 配置 → 后台起 Resin，等 `/imart` 起来 |
| `./gradlew imRestart`・`imStop`・`imRun` | 重启・停・前台跑（`imRun` 把日志打到输出里，停止任务时正常停 Resin） |
| `./gradlew imDebug` | 同 imRun，另开 JVM 调试端口（`intraMart.resin.debugPort`，缺省 5005），IDE 用 Remote JVM Debug 连 |
| `./gradlew build` | 外加 `build/libs/imart.war` |
| `./gradlew imProject --refresh imSetup` | 重新取官方仓库的版本目录与版本索引（拿到新出的补丁）并重新准备平台 |

- 源码布局照官方的モジュールプロジェクト：`src/main/java`・`resources` 打成 jar 进 `WEB-INF/lib`；`src/main/conf`→`WEB-INF/conf`、`schema`→`WEB-INF/schema`、`plugin`→`WEB-INF/plugin`、`jssp`→`WEB-INF/jssp`（里面是 `src/<自己的目录>/…`）、`storage`→`WEB-INF/storage`、`public` 与 `webapp`→站点根。都可以没有；同一路径以后者为准。
- `./gradlew imModule`（`build` 也会跑）把同一份源码打成ユーザモジュール `build/libs/<工程名>-<version>.imm`：IM-Juggling 的「ユーザモジュール」标签页能导入，官方 Docker Stacks 的 `additional-modules`、Accel CLI 的 `deploy --additional-file` 也收。module.xml 的 id 缺省 `group.工程名`，可在 `intraMart { module { id = …; name = …; vendor = …; description = … } }` 改；版本用工程的 `version`（要像 `1.0.0`）。
- 编译用的 intra-mart API：缺省取自官方 Maven 仓库 `https://maven.intra-mart.jp/`（BOM 按 `version` 自动选），在 `dependencies` 里不带版本地写要用的模块，如 `compileOnly 'jp.co.intra_mart:im_logic'`。`intraMart { libraries = 'platform' }` 则改为平台的 531 个 jar 全在类路径上，不用写依赖（离线可用）。
- 选了哪些模块、为什么不选、打了哪些补丁，看 `build/intra-mart/setup/resolution.txt`。
- 补丁：缺省打各模块同版本号的最新 `-PATCH_n`（同 IM-Juggling「全てのパッチを適用する」）；`patches = false` 不打，`patch("im_workflow")` 只打指定的。
- 升级・打补丁后：系统管理员「テナント環境セットアップ」执行到「テナント環境は最新です」，数据库按 schema 版本做差分（`im_schema_update`）。
- setup 与 build 分开但自动衔接：解决结果放 `build/intra-mart/setup`，`build` 前自动跑 `imSetup`，设定没变就跳过。不想自动（如 CI 里不想下载）：`intraMart.autoSetup=false`，这时平台没准备或设定变了会报错并列出哪一项变了。
- 插件的日志・报错都是英文。
- 工程里不放展开的平台：imm 与编译用 jar 在 `~/.gradle/caches/intra-mart/{repository,jars}`（多个工程共用）；WAR 目录 `build/intra-mart/webapp` 只在平台变了时从 imm 整个展开，平时只叠加本工程改了的文件。工程里没有运行时目录：本机 Resin 的配置・日志・运行数据在 `~/.intra-mart/resin/<工程名>-<哈希>/`（Resin 本体不改），`intraMart.runtimeDir` 可改。storage（租户的业务数据）也不在工程里：缺省 `~/.intra-mart/storage/<库名>`（库名取自 datasource.url），`intraMart.storageDir` 可改。
- 职责分开：①构建（`imSetup`・`build`）出的 WAR 与环境无关，不带数据库与 storage 路径；②本机部署（`imResinConfig`・`imStart`…）把本机的数据库・storage 写进它生成的 Resin 配置。本机设定 `intraMart.*` 是普通的 Gradle 属性，照 Gradle 的优先顺序：`-P` ＞ 环境变量 `ORG_GRADLE_PROJECT_…` ＞ `~/.gradle/gradle.properties` ＞ 工程的 `gradle.properties`；`build.gradle` 的 `intraMart { }` 里直接写的最优先。

- 部署到别的 Resin：WAR 里不带数据库与 storage 路径，那台 Resin 的 `resin.xml` 在 `<cluster id="app">` 下定义 `jdbc/default`，`resin.properties` 的 `jvm_args` 加 `-Dim.storage.dir=<绝对路径，无空格>`。本机 `imStart` 生成的 `~/.intra-mart/resin/<工程名>-<哈希>/conf/` 就是现成的样例。
- 数据库等中间件放容器：插件不管 Docker，应用工程自己放一份 `compose.yaml`（PostgreSQL・mailpit 用公开镜像；Solr・Cassandra 照官方 [docker-stacks](https://github.com/accelplatform/docker-stacks) 的 Dockerfile 自建，压缩包来自 intra-mart ライセンスポータル），然后 `intraMart.datasource.url` 指容器的端口，Solr・Cassandra 的 `solr-config.xml`・`cassandra-config.xml` 放 `src/main/conf`，相应模块用 `include(…)` 从缺省排除里拿回来。Resin 用本机的，官方栈里的 Resin・httpd・juggling-build-war 不需要。

## 调试・热部署

- `imDebug` 起 Resin 并开 JDWP 端口（缺省 5005）；IDE 用远程 JVM 调试（JDWP attach）连 `localhost:5005`，断点停在 `src/main/java` 的代码里。
- 改了 Java 方法体：调试器连着时编译，让 IDE 重新加载类即生效（JVM HotSwap，只能换方法体）。改类结构或依赖要 `imRestart`：Windows 上 Resin 开着时 `WEB-INF/lib` 的 jar 被占用换不了。
- 改了 `src/main/webapp` 下的静态文件・JSP：跑 `imWebapp`（Resin 开着也行）即生效。
- `imStart` 起的 Resin 和 IDE 自己起的 Resin 不能同时跑（同端口 8080・6800）。
- JSSP（スクリプト開発モデル）调试：只在用官方的 VS Code 扩展（`intra-mart.accelplatform-vscode`）或 e Builder 调服务端脚本时才需要。装官方的调试器模块，并给 Resin 两个 JVM 参数，扩展连 9000（与 Java 的 JDWP 无关；没有这两个客户端就不用装）：

  ```kotlin
  intraMart {
      applications.add("module.pack.e_builder")           // モジュール開発支援ライブラリ（含 e_builder_debugger；8.0.40 对应 8.0.4）
      // applications.add("module.pack.ai_native_development_pack") // Accel Orbit：Staging・MCP 等 AI 开发支援（8.0.40 起，初期评价版）
      resin { jvmArgs = listOf("-Djp.co.intra_mart.system.javascript.Debugger.protocol=dap",
                               "-Djp.co.intra_mart.system.javascript.Debugger.port=9000") }
  }
  ```

## 构建・发布插件

```bash
./gradlew build                  # build/libs/intra-mart-gradle-<版本>.jar，含 validatePlugins
./gradlew publishToMavenLocal    # 发布到本机 ~/.m2（应用工程 settings 加 mavenLocal()）
./gradlew publish                # 发到 build/maven-repo（Maven 布局，含插件 marker）
./gradlew publishPlugins         # 发布到 Gradle Plugin Portal（要 ~/.gradle/gradle.properties 里的 gradle.publish.key／secret）
```

发新版：改 `version`，`./gradlew publish`，把 `build/maven-repo` 的内容提交到 `maven` 分支推上去（该分支只放产物，与 main 无共同历史）。

开发插件时，应用工程也可以直接引用本仓源码（改了插件自动重编）：

```groovy
// 应用工程的 settings.gradle
pluginManagement { includeBuild '../intra-mart-gradle' }
```

新建应用工程：普通的 Gradle Java 工程，按上面「用法」加插件与 `intraMart { version = … }` 即可。第一次 `./gradlew build` 后让 IDE 重新导入 Gradle 工程，`jp.co.intra_mart.*` 才能补全。

## 本仓

| 路径 | 内容 |
|---|---|
| `src/main/java/io/github/jaxsh/intramart` | 插件源码。模块解决 `PlatformResolver`・排序 `HierarchyOrder`・拼装 `ImAssembler`・web.xml `WebXmlBuilder`・WAR 目录 `WebappTask`・编译依赖 `ImLibrariesTask`／`MavenBom`・Resin `ResinConfigTask`／`ResinTask` |
| `src/main/resources/io/github/jaxsh/intramart` | 缺省不选的模块表、juggling.log 模板 |
| `.claude/gradlew.sh` | Claude Code 的 Bash 沙箱里跑 gradlew 用（普通终端・IDE 不需要） |
| `LICENSE` | Apache License 2.0 |

插件 ID `io.github.jaxsh.intra-mart`、group `io.github.jaxsh` 按 Gradle Plugin Portal 的命名空间规则（个人账号用 `io.github.<用户名>`）；`build.gradle.kts` 里带 `com.gradle.plugin-publish` 的元数据（website・vcsUrl・tags・Configuration Cache 不支持的声明）。
