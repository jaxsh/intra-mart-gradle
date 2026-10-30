package io.github.jaxsh.intramart;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * module-order 的排序。与官方开源的 @intra-mart/juggling-core 0.1.5（LGPL-3.0） dist/model/hierarchy.js 的 sortHierarchalList 同一算法：
 * <ol>
 * <li>每个模块按依赖声明顺序，挂接满足版本条件（等于 verified，或在 min〜max 内）的模块；挂不全就报错；</li>
 * <li>权重 = 直接依赖数 + 递归累加未访问依赖的权重（访问集合在一次递归里共享）；</li>
 * <li>按权重升序稳定排序（Collections.sort）。</li>
 * </ol>
 * 输出依赖输入顺序：输入要是 GUI 模块容器的迭代顺序（见 {@code PlatformResolver.ContainerOrder}）。
 */
public final class HierarchyOrder {

    /** 依赖：模块 ID、verified 版本、min・max。 */
    public record Dependency(String moduleId, String verified, String min, String max) {
    }

    /** 排序输入。 */
    public record Node(String id, String version, List<Dependency> dependencies) {
    }

    private static final class Info {
        final Node node;
        final Version version;
        final List<Info> dependsOn = new ArrayList<>();
        int priority = -1;

        Info(final Node node) {
            this.node = node;
            this.version = Version.parse(node.version());
        }
    }

    private HierarchyOrder() {
    }

    public static List<Node> sort(final List<Node> input) {
        final List<Info> list = new ArrayList<>();
        for (final Node n : input) {
            if (list.stream().noneMatch(i -> i.node.id().equals(n.id()))) {
                list.add(new Info(n));
            }
        }

        for (final Info info : list) {
            for (final Dependency d : info.node.dependencies()) {
                if (list.stream().noneMatch(c -> d.moduleId().equals(c.node.id()) && accepts(d, c.version))) {
                    throw new IllegalStateException("Unresolved dependency: " + info.node.id() + " " + info.node.version()
                            + " -> " + d.moduleId() + " " + d.verified());
                }
            }
        }
        for (final Info info : list) {
            for (final Dependency d : info.node.dependencies()) {
                for (final Info c : list) {
                    if (d.moduleId().equals(c.node.id())) {
                        setDependOn(info, c);
                    }
                }
            }
            if (info.node.dependencies().size() != info.dependsOn.size()) {
                throw new IllegalStateException("Incompatible dependencies: " + info.node.id() + " " + info.node.version());
            }
        }
        for (final Info info : list) {
            info.priority = weight(info, new ArrayList<>());
        }

        final List<Info> sorted = new ArrayList<>(list);
        Collections.sort(sorted, Comparator.comparingInt(i -> i.priority));
        final List<Node> out = new ArrayList<>(sorted.size());
        for (final Info i : sorted) {
            out.add(i.node);
        }
        return out;
    }

    /** hierarchy.js 的 setDependOnInfo：已挂则跳过；找第一个同 ID 的依赖声明，版本满足就挂。 */
    private static void setDependOn(final Info info, final Info candidate) {
        if (info.dependsOn.contains(candidate)) {
            return;
        }
        for (final Dependency d : info.node.dependencies()) {
            if (!d.moduleId().equals(candidate.node.id())) {
                continue;
            }
            if (accepts(d, candidate.version)) {
                info.dependsOn.add(candidate);
                return;
            }
        }
    }

    private static boolean accepts(final Dependency d, final Version v) {
        if (Version.parse(d.verified()).equals(v)) {
            return true;
        }
        return compare(Version.parse(d.min()), v) <= 0 && compare(Version.parse(d.max()), v) >= 0;
    }

    private static int weight(final Info info, final List<Info> visited) {
        visited.add(info);
        int w = info.dependsOn.size();
        for (final Info d : info.dependsOn) {
            if (!visited.contains(d)) {
                visited.add(d);
                w += weight(d, visited);
            }
        }
        return w;
    }

    static int compare(final Version a, final Version b) {
        int c = Integer.compare(a.major(), b.major());
        if (c == 0) {
            c = Integer.compare(a.minor(), b.minor());
        }
        if (c == 0) {
            c = Integer.compare(a.micro(), b.micro());
        }
        if (c == 0) {
            c = (a.qualifier() == null ? "" : a.qualifier()).compareTo(b.qualifier() == null ? "" : b.qualifier());
        }
        return c;
    }
}
