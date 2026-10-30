package io.github.jaxsh.intramart;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * 官方拼 WAR 时一起写的记录文件（运行时不读）。格式照 @intra-mart/juggling-core 0.1.5（dist/build/export-war.js・packaging.js・structure-dump.js）：
 * <ul>
 * <li>META-INF/MANIFEST.MF：Ant 风格，Ant-Version・Created-By 空，CRLF；</li>
 * <li>WEB-INF/juggling.log：Summary 的 HTML（固定内容＋时刻），LF、末尾无换行；</li>
 * <li>WEB-INF/juggling.structure.txt：base 与各应用 pack 的构成树，[o] 选中・[x] 不选，「名称 (id@version)」，LF。</li>
 * </ul>
 */
final class JugglingRecords {

    private JugglingRecords() {
    }

    static void write(final Path jugglingIm, final Path warRoot) throws IOException {
        final Path manifest = warRoot.resolve("META-INF/MANIFEST.MF");
        Files.createDirectories(manifest.getParent());
        Files.writeString(manifest, "Manifest-Version: 1.0\r\nAnt-Version: \r\nCreated-By: \r\n\r\n", StandardCharsets.UTF_8);

        try (InputStream in = JugglingRecords.class.getResourceAsStream("juggling.log.template")) {
            final String html = new String(in.readAllBytes(), StandardCharsets.UTF_8).replace("@TIMESTAMP@",
                    LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            Files.writeString(warRoot.resolve("WEB-INF/juggling.log"), html, StandardCharsets.UTF_8);
        }

        Files.writeString(warRoot.resolve("WEB-INF/juggling.structure.txt"), structure(jugglingIm), StandardCharsets.UTF_8);
    }

    /** 模块（含 pack）：名称・选中・子模块。 */
    private record Node2(String id, String version, String name, boolean selected, List<String> children) {
    }

    static String structure(final Path jugglingIm) throws IOException {
        final Document doc;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(jugglingIm))) {
            final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            doc = f.newDocumentBuilder().parse(in);
        } catch (final IOException e) {
            throw e;
        } catch (final Exception e) {
            throw new IOException("Cannot read " + jugglingIm, e);
        }
        // 取第一个（最新的）structure
        final Element structure = (Element) doc.getElementsByTagNameNS("*", "structure").item(0);
        final Map<String, Node2> modules = new HashMap<>();
        for (final Element m : children(first(structure, "modules"))) {
            final List<String> kids = new ArrayList<>();
            final Element c = first(m, "children");
            if (c != null) {
                for (final Element r : children(c)) {
                    kids.add(key(r));
                }
            }
            final Element name = first(m, "name");
            modules.put(key(m), new Node2(m.getAttribute("id"), m.getAttribute("version"),
                    name == null ? m.getAttribute("id") : name.getTextContent(), "true".equals(m.getAttribute("selected")), kids));
        }
        final List<String> roots = new ArrayList<>();
        roots.add(key(first(structure, "base")));
        final Element apps = first(structure, "applications");
        if (apps != null) {
            for (final Element a : children(apps)) {
                roots.add(key(a));
            }
        }
        final StringBuilder sb = new StringBuilder();
        for (final String root : roots) {
            tree(sb, modules, root, "", true, true);
        }
        return sb.toString();
    }

    // 根：「[o] …」；子：" " ＋ 各上级（最后一个的为 "   "，否则 "│  "）＋ 自己（最后一个 "└ "，否则 "├ "）
    private static void tree(final StringBuilder sb, final Map<String, Node2> modules, final String key, final String indent,
            final boolean last, final boolean root) {
        final Node2 n = modules.get(key);
        if (n == null) {
            return;
        }
        if (!root) {
            sb.append(' ').append(indent).append(last ? "└ " : "├ ");
        }
        sb.append(n.selected() ? "[o] " : "[x] ").append(n.name()).append(" (").append(n.id()).append('@').append(n.version()).append(")\n");
        final String childIndent = root ? "" : indent + (last ? "   " : "│  ");
        for (int i = 0; i < n.children().size(); i++) {
            tree(sb, modules, n.children().get(i), childIndent, i == n.children().size() - 1, false);
        }
    }

    private static String key(final Element e) {
        return e.getAttribute("id") + "@" + e.getAttribute("version");
    }

    private static Element first(final Element parent, final String local) {
        for (final Element e : children(parent)) {
            if (local.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    private static List<Element> children(final Element parent) {
        final List<Element> out = new ArrayList<>();
        final NodeList nl = parent.getChildNodes();
        for (int i = 0; i < nl.getLength(); i++) {
            final Node n = nl.item(i);
            if (n instanceof Element e) {
                out.add(e);
            }
        }
        return out;
    }
}
