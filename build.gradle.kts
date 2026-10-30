// intra-mart Accel Platform 用 Gradle 插件：用 Gradle 替 IM-Juggling 解决模块、拼 WAR、起停 Resin。
// 拼装规则与出处写在各类的注释里。
import org.gradle.plugin.compatibility.compatibility

plugins {
    `java-gradle-plugin`
    `maven-publish`
    id("com.gradle.plugin-publish") version "2.2.1"
}

group = "io.github.jaxsh"
version = "0.1.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

dependencies {
    // web.xml 模板的条件式：与官方 WarTask（im_juggling_ant 1.0.8 的 lib/ognl-2.7.3.jar）同一版本
    implementation("ognl:ognl:2.7.3")
}

// 插件 jar 字节可复现：多个工程 includeBuild 同一份源码会轮流重编，jar 不变则用插件的任务不会因「实现变了」而重跑
tasks.withType<AbstractArchiveTask>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    // 插件跑在 Gradle 的 JVM 里：Gradle 9 最低 JDK 17（IDE 的 Gradle JVM 常是 17），按 17 出字节码
    options.release = 17
}

// Gradle Plugin Portal 的规则（plugins.gradle.org/docs/publish-plugin，2026-10-30）：ID 与 group 要能追溯到作者（个人用 io.github.<GitHub 用户名>），
// 两者同一顶级命名空间；首版人工审核，改 ID／group 再审；website・vcsUrl・displayName・description・tags 必填；
// 要声明 Configuration Cache 支持与否；不收 SNAPSHOT；理想上开源。
gradlePlugin {
    website = "https://github.com/jaxsh/intra-mart-gradle"
    vcsUrl = "https://github.com/jaxsh/intra-mart-gradle"
    plugins {
        register("intraMart") {
            id = "io.github.jaxsh.intra-mart"
            implementationClass = "io.github.jaxsh.intramart.AccelPlatformPlugin"
            displayName = "intra-mart Accel Platform"
            description = "Builds intra-mart Accel Platform web applications without IM-Juggling: resolves the platform modules " +
                    "for a given version (with patches), assembles the WAR exactly like the official tool, compiles against " +
                    "the official Maven BOM, and runs a local Resin for development."
            tags = listOf("intra-mart", "intramart", "accel-platform", "war", "resin", "juggling")
            compatibility {
                features {
                    // 配置阶段按版本选 BOM、afterEvaluate 加仓库与排除：没按 Configuration Cache 验证过
                    configurationCache = false
                }
            }
        }
    }
}

tasks.withType<Javadoc>().configureEach {
    // 注释是中文；记录类的参数不逐个写 @param
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).addBooleanOption("Xdoclint:none", true)
    (options as StandardJavadocDocletOptions).charSet = "UTF-8"
}

// 不经 Plugin Portal 的分发：`./gradlew publish` 把 jar・pom・插件 marker 发到 build/maven-repo（Maven 布局），
// 再把该目录内容推到本仓的 maven 分支；使用方在 pluginManagement 里加
// maven("https://raw.githubusercontent.com/jaxsh/intra-mart-gradle/maven/") 即可按 id＋version 取到。
publishing {
    repositories {
        maven {
            name = "repoBranch"
            url = uri(layout.buildDirectory.dir("maven-repo"))
        }
    }
}
