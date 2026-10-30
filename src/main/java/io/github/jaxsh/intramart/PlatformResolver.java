package io.github.jaxsh.intramart;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.function.Consumer;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * 按「base 版本＋应用 pack」解决出要展开的模块与 module-order，等同在 IM-Juggling 里建工程、全选、保存：
 * <ol>
 * <li>应用 pack 的版本：app/application-module-list.xml 里 required-products 含 base 该版本的最高版本（可钉）；</li>
 * <li>从 base 与各应用 pack 起按 module-pack.xml 逐层展开（成员版本钉死）；</li>
 * <li>补丁：叶模块按 metadata.zip 的版本索引换成同版本号的最新 -PATCH_n（IM-Juggling「全てのパッチを適用する」），所在 pack 的成员引用跟着换；</li>
 * <li>选择：全选，减缺省不选（default-excludes.properties）与用户 exclude，加回用户 include；上级 pack 全未选则不选；依赖闭包必须满足；</li>
 * <li>module-order：按 {@link ContainerOrder}（GUI 工程文件里模块列表的顺序）输入 {@link HierarchyOrder}。</li>
 * </ol>
 * 目录与元数据的要点：base/base-module-list.xml 与 app/application-module-list.xml 分别只在各自分区；
 * module-pack.xml 的成员可带 optional 且文件里有 XML 注释，module.xml 有把 min 写在 module-id 上的（im_menu_ws 8.0.1），所以都按元素解析；
 * module.xsd 没有互斥声明，AP 服务器专属只靠 app:resin／app:payara 这类 tag。
 */
public final class PlatformResolver {

    public static final String PREFIX = "jp.co.intra_mart.";

    /** 解决结果。 */
    public record Result(ModuleInfo base, List<ModuleInfo> applications, List<ModuleInfo> all, Set<String> selected,
            List<ModuleInfo> moduleOrder, Map<String, Product> products, Map<String, String> excludedBecause,
            Map<String, String> patched) {
    }

    /** 补丁：enabled＝打不打；only 不空时只给这些模块打（官方「個別に適用」，非推奨）。 */
    public record Patches(boolean enabled, Set<String> only) {
    }

    /** base/app 目录文件里一个产品版本的 category・date・required-products。 */
    public record Product(String category, String date, List<List<String[]>> requiredProducts) {
    }

    private final ModuleRepository repo;
    private final Consumer<String> log;

    public PlatformResolver(final ModuleRepository repo, final Consumer<String> log) {
        this.repo = repo;
        this.log = log;
    }

    public Result resolve(final String baseId, final String baseVersion, final List<String> applicationIds,
            final Map<String, String> pinnedVersions, final Collection<String> excludes, final Collection<String> includes,
            final Patches patches, final boolean refreshCatalog) throws IOException {
        final Map<String, Product> products = new HashMap<>();
        readCatalog(repo.catalog("base", "base-module-list.xml", refreshCatalog), products);
        readCatalog(repo.catalog("app", "application-module-list.xml", refreshCatalog), products);
        if (!products.containsKey(baseId + " " + baseVersion)) {
            throw new IOException("Not in base-module-list.xml: " + baseId + " " + baseVersion);
        }

        final List<String[]> roots = new ArrayList<>();
        roots.add(new String[] {baseId, baseVersion});
        for (final String app : applicationIds) {
            final String id = full(app);
            final String v = pinnedVersions.containsKey(id) ? pinnedVersions.get(id) : latestFor(products, id, baseId, baseVersion);
            roots.add(new String[] {id, v});
        }

        // 逐层展开（同 ID 只取第一次出现的版本）
        final Map<String, ModuleInfo> byId = new LinkedHashMap<>();
        final Map<String, List<String>> parents = new HashMap<>();
        final Deque<String[]> queue = new ArrayDeque<>(roots);
        while (!queue.isEmpty()) {
            final String[] k = queue.poll();
            if (byId.containsKey(k[0])) {
                continue;
            }
            final ModuleInfo m = ModuleInfo.read(repo.metadata(k[0], k[1]), k[0], k[1]);
            byId.put(m.id(), m);
            for (final ModuleInfo.Child c : m.children()) {
                parents.computeIfAbsent(c.id(), x -> new ArrayList<>()).add(m.id());
                queue.add(new String[] {c.id(), c.version()});
            }
        }
        log.accept("Modules: " + byId.size() + " (" + roots.size() + " top-level packs)");

        // 补丁（叶模块；pack 的补丁会改成员，这里不处理）
        final Map<String, String> patched = new LinkedHashMap<>();
        if (patches.enabled()) {
            final Set<String> only = new HashSet<>();
            for (final String o : patches.only()) {
                only.add(full(o));
            }
            final RepositoryIndex index = RepositoryIndex.load(List.of(
                    repo.catalog("base", "metadata.zip", refreshCatalog), repo.catalog("app", "metadata.zip", refreshCatalog)));
            for (final ModuleInfo m : new ArrayList<>(byId.values())) {
                if (m.pack() || !only.isEmpty() && !only.contains(m.id())) {
                    continue;
                }
                final String p = index.latestPatch(m.id(), m.version());
                if (p != null) {
                    byId.put(m.id(), ModuleInfo.read(repo.metadata(m.id(), p), m.id(), p));
                    patched.put(m.id(), m.version() + " -> " + p);
                }
            }
            if (!patched.isEmpty()) {
                for (final ModuleInfo pk : new ArrayList<>(byId.values())) {
                    if (!pk.pack()) {
                        continue;
                    }
                    final List<ModuleInfo.Child> cs = new ArrayList<>();
                    boolean changed = false;
                    for (final ModuleInfo.Child c : pk.children()) {
                        final ModuleInfo t = byId.get(c.id());
                        if (patched.containsKey(c.id()) && t != null) {
                            cs.add(new ModuleInfo.Child(c.id(), t.version(), c.optional()));
                            changed = true;
                        } else {
                            cs.add(c);
                        }
                    }
                    if (changed) {
                        byId.put(pk.id(), new ModuleInfo(pk.id(), pk.version(), true, pk.name(), pk.vendor(), pk.description(),
                                pk.dependencies(), pk.tags(), List.copyOf(cs)));
                    }
                }
            }
            log.accept("Patches: " + patched.size());
        }

        // 选择
        final Map<String, String> excludedBecause = new LinkedHashMap<>();
        final Properties defaults = defaultExcludes();
        for (final String id : defaults.stringPropertyNames()) {
            excludedBecause.put(id, defaults.getProperty(id));
        }
        for (final String e : excludes) {
            excludedBecause.put(full(e), "excluded in the build script");
        }
        for (final String i : includes) {
            excludedBecause.remove(full(i));
        }
        final Set<String> rootIds = new HashSet<>();
        for (final String[] r : roots) {
            rootIds.add(r[0]);
        }
        final Map<String, Boolean> reach = new HashMap<>();
        final Set<String> selected = new LinkedHashSet<>();
        for (final ModuleInfo m : byId.values()) {
            if (reachable(m.id(), byId, parents, rootIds, excludedBecause, reach)) {
                selected.add(m.id());
            } else if (!excludedBecause.containsKey(m.id())) {
                excludedBecause.put(m.id(), "parent pack not selected");
            }
        }
        final List<String> problems = new ArrayList<>();
        for (final String id : selected) {
            for (final HierarchyOrder.Dependency d : byId.get(id).dependencies()) {
                final ModuleInfo t = byId.get(d.moduleId());
                if (t == null) {
                    problems.add(id + " -> " + d.moduleId() + " (not in the module tree)");
                } else if (!selected.contains(t.id())) {
                    problems.add(id + " -> " + d.moduleId() + " (not selected: " + excludedBecause.get(t.id()) + ")");
                } else if (HierarchyOrder.compare(Version.parse(d.min()), Version.parse(t.version())) > 0) {
                    problems.add(id + " -> " + d.moduleId() + " requires >= " + d.min() + ", tree has " + t.version());
                }
            }
        }
        if (!problems.isEmpty()) {
            throw new IOException("Unsatisfied dependencies (include the missing module, or exclude the modules that depend on it):\n  " + String.join("\n  ", problems));
        }

        // module-order
        final List<ModuleInfo> all = new ArrayList<>(byId.values());
        final List<HierarchyOrder.Node> input = new ArrayList<>();
        for (final ModuleInfo m : ContainerOrder.order(all)) {
            if (selected.contains(m.id())) {
                input.add(new HierarchyOrder.Node(m.id(), m.version(), m.dependencies()));
            }
        }
        final List<ModuleInfo> order = new ArrayList<>();
        for (final HierarchyOrder.Node n : HierarchyOrder.sort(input)) {
            order.add(byId.get(n.id()));
        }

        final List<ModuleInfo> apps = new ArrayList<>();
        for (int i = 1; i < roots.size(); i++) {
            apps.add(byId.get(roots.get(i)[0]));
        }
        return new Result(byId.get(baseId), apps, all, selected, order, products, excludedBecause, patched);
    }

    private static boolean reachable(final String id, final Map<String, ModuleInfo> byId, final Map<String, List<String>> parents,
            final Set<String> roots, final Map<String, String> excluded, final Map<String, Boolean> memo) {
        final Boolean m = memo.get(id);
        if (m != null) {
            return m;
        }
        memo.put(id, Boolean.FALSE);
        boolean r = false;
        if (!excluded.containsKey(id)) {
            if (roots.contains(id)) {
                r = true;
            } else {
                for (final String p : parents.getOrDefault(id, List.of())) {
                    if (byId.containsKey(p) && reachable(p, byId, parents, roots, excluded, memo)) {
                        r = true;
                        break;
                    }
                }
            }
        }
        memo.put(id, r);
        return r;
    }

    /** required-products 含 base 该版本的应用版本里最高的。 */
    private static String latestFor(final Map<String, Product> products, final String appId, final String baseId,
            final String baseVersion) throws IOException {
        String best = null;
        for (final Map.Entry<String, Product> e : products.entrySet()) {
            final String[] k = e.getKey().split(" ");
            if (!k[0].equals(appId)) {
                continue;
            }
            boolean ok = false;
            for (final List<String[]> group : e.getValue().requiredProducts()) {
                for (final String[] p : group) {
                    if (p[0].equals(baseId) && p[1].equals(baseVersion)) {
                        ok = true;
                    }
                }
            }
            if (ok && (best == null || HierarchyOrder.compare(Version.parse(k[1]), Version.parse(best)) > 0)) {
                best = k[1];
            }
        }
        if (best == null) {
            throw new IOException("No version of " + appId + " supports " + baseId + " " + baseVersion + " (application-module-list.xml)");
        }
        return best;
    }

    private static void readCatalog(final Path f, final Map<String, Product> out) throws IOException {
        final Element root;
        try (InputStream in = Files.newInputStream(f)) {
            final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setIgnoringComments(true);
            root = dbf.newDocumentBuilder().parse(in).getDocumentElement();
        } catch (final IOException e) {
            throw e;
        } catch (final Exception e) {
            throw new IOException("Cannot read " + f, e);
        }
        final var cats = root.getElementsByTagNameNS("*", "category");
        for (int i = 0; i < cats.getLength(); i++) {
            final Element cat = (Element) cats.item(i);
            final String catId = text(child(cat, "id"));
            final Element modules = child(cat, "modules");
            if (modules == null) {
                continue;
            }
            for (Node n = modules.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element m) || !"module".equals(m.getLocalName())) {
                    continue;
                }
                final List<List<String[]>> req = new ArrayList<>();
                final Element rps = child(m, "required-products");
                if (rps != null) {
                    for (Node rn = rps.getFirstChild(); rn != null; rn = rn.getNextSibling()) {
                        if (rn instanceof Element rp && "required-product".equals(rp.getLocalName())) {
                            final List<String[]> group = new ArrayList<>();
                            for (Node pn = rp.getFirstChild(); pn != null; pn = pn.getNextSibling()) {
                                if (pn instanceof Element p && "product".equals(p.getLocalName())) {
                                    group.add(new String[] {p.getAttribute("id"), p.getAttribute("version")});
                                }
                            }
                            req.add(group);
                        }
                    }
                }
                out.put(text(child(m, "id")) + " " + text(child(m, "version")), new Product(catId, text(child(m, "date")), req));
            }
        }
    }

    private static Element child(final Element parent, final String localName) {
        if (parent == null) {
            return null;
        }
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    private static String text(final Element e) {
        return e == null ? "" : e.getTextContent().trim();
    }

    static String full(final String id) {
        return id.startsWith(PREFIX) ? id : PREFIX + id;
    }

    static Properties defaultExcludes() throws IOException {
        final Properties p = new Properties();
        try (InputStream in = PlatformResolver.class.getResourceAsStream("default-excludes.properties")) {
            if (in == null) {
                throw new IOException("default-excludes.properties is missing from the plugin");
            }
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    /**
     * IM-Juggling GUI 写出的 juggling.im 里 &lt;modules&gt; 的顺序：不是插入顺序，而是按 Java HashMap 散列
     * （31*(31+id.hashCode())+version.hashCode()，容量按 0.75 翻倍）的桶序，同桶内按插入先后（观察 GUI 的工程文件得出）。
     * 排序结果依赖输入顺序，所以要照它。
     * juggling-core 自己改成了按插入顺序写 &lt;modules&gt;（其 compat ledger COMPAT-076），这里保留 GUI 的顺序。
     */
    static final class ContainerOrder {
        private ContainerOrder() {
        }

        static List<ModuleInfo> order(final List<ModuleInfo> insertion) {
            // Java HashMap 的容量：从 16 起，元素数到 0.75×容量就翻倍
            int cap = 16;
            for (int n = 1; n <= insertion.size(); n++) {
                if (n >= cap - (cap >>> 2)) {
                    cap <<= 1;
                }
            }
            final int mask = cap - 1;
            final List<int[]> idx = new ArrayList<>();
            for (int i = 0; i < insertion.size(); i++) {
                final ModuleInfo m = insertion.get(i);
                final int h = 31 * (31 + m.id().hashCode()) + m.version().hashCode();
                idx.add(new int[] {((h ^ (h >>> 16)) & 0x7fffffff) & mask, i});
            }
            idx.sort((a, b) -> a[0] != b[0] ? Integer.compare(a[0], b[0]) : Integer.compare(a[1], b[1]));
            final List<ModuleInfo> out = new ArrayList<>();
            for (final int[] x : idx) {
                out.add(insertion.get(x[1]));
            }
            return out;
        }
    }
}
