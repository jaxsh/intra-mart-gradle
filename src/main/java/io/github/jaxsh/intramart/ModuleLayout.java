package io.github.jaxsh.intramart;

import java.util.List;

/**
 * 用户模块的源码布局（官方「モジュールプロジェクト」的定義済みディレクトリ，accelplatform.github.io module-spec/project-structure）
 * 与 imm 构件种别・WAR 内位置的对应。java・resources 走 Gradle 的 jar（imm 里的 -main.jar），不在这张表里。
 * <p>
 * 构件 zip 的内部路径＝目录内的相对路径（官方 imm 里：jssp-external 里是 product/src/…，storage 里是 system/…・public/…，
 * public・webapp 里是站点根相对路径，conf・schema・plugin 是各自目录的根相对路径）。
 */
final class ModuleLayout {

    /** src/main 下的目录名、imm 构件种别、展开到 WAR 的位置（"" 为站点根）。 */
    record Dir(String source, String kind, String warDir) {
    }

    /** 顺序即同一路径冲突时的覆盖顺序（后者赢）：webapp 放最后，可覆盖 public。 */
    static final List<Dir> DIRS = List.of(
            new Dir("conf", "conf", "WEB-INF/conf"),
            new Dir("schema", "schema", "WEB-INF/schema"),
            new Dir("plugin", "plugin", "WEB-INF/plugin"),
            new Dir("jssp", "jssp-external", "WEB-INF/jssp"),
            new Dir("storage", "storage", "WEB-INF/storage"),
            new Dir("public", "public", ""),
            new Dir("webapp", "webapp", ""));

    private ModuleLayout() {
    }
}
