#!/usr/bin/env bash
# 受限环境（Claude Code 的 Bash 沙箱）里跑 gradlew。
# 沙箱不允许在系统 TEMP 下建 AF_UNIX 套接字，JDK 的 NIO Selector 会报 "Unable to establish loopback connection"；
# 用 JAVA_TOOL_OPTIONS 把 jdk.net.unixdomain.tmpdir 指到项目内，客户端和它起的守护进程都会继承
# （写在 org.gradle.jvmargs 里不行：Gradle 不把它当启动参数传给守护进程）。
# IDE 或普通终端里不需要这个脚本，直接 ./gradlew。JDK 取 IM_JAVA_HOME，没有就 JAVA_HOME。
set -e
root=$(cd "$(dirname "$0")/.." && pwd)
mkdir -p "$root/.gradle/uds"
export JAVA_TOOL_OPTIONS="-Djdk.net.unixdomain.tmpdir=$(cygpath -aw "$root/.gradle/uds")"
export JAVA_HOME="${IM_JAVA_HOME:-${JAVA_HOME:?set IM_JAVA_HOME or JAVA_HOME to a JDK 17+}}"
cd "$root"
exec ./gradlew --console=plain "$@"
