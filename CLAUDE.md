# intra-mart-gradle

Gradle 插件 `io.github.jaxsh.intra-mart`（group `io.github.jaxsh`，Java 包 `io.github.jaxsh.intramart`；ID・group 按 Gradle Plugin Portal 的命名空间规则）：用 Gradle 替 IM-Juggling——按版本解决 intra-mart Accel Platform 的模块，拼 WAR，在本机 Resin 上跑。本仓就是插件工程，用法见 [README.md](README.md)。

## 目录

| 路径 | 内容 |
|---|---|
| `src/main/java/io/github/jaxsh/intramart` | 插件源码：模块解决 `PlatformResolver`・排序 `HierarchyOrder`・拼装 `ImAssembler`・web.xml `WebXmlBuilder`・WAR 目录 `WebappTask`・编译依赖 `ImLibrariesTask`／`MavenBom`・Resin `ResinConfigTask`／`ResinTask` |
| `src/main/resources/io/github/jaxsh/intramart` | 缺省不选的模块表 `default-excludes.properties`、juggling.log 模板 |
| `.claude/gradlew.sh` | Claude Code 的 Bash 沙箱里跑 gradlew 必须经它（AF_UNIX 套接字目录、JDK 21） |

## 规矩

- 中文沟通，日文只用于固有名词。
- 不猜：产品行为去官网（document.intra-mart.jp）核，或拿官方 WarTask／@intra-mart/juggling-core 的产物逐文件对；探不到的写明试过什么。
- 结论标「证」或「推」。
- 一批一看，不擅自扩大范围。
- 不留过程文档：规则与出处写在对应类的注释里，用法进 README，发布规则进 build.gradle.kts；验证结论只在会话里报，不进仓库。还没核实的事项临时放 docs/findings.md，核完就删。
- 插件的日志・报错・任务说明是英文；代码注释是中文。
