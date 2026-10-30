package io.github.jaxsh.intramart;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * 仓库目录里一个模块的元数据：module.xml（依赖・tag・名称）、pack 的 module-pack.xml（成员与 optional）、message*.properties。
 * 按元素解析（DOM，注释自动略过）：module.xml 写法不一，如 im_menu_ws 8.0.1 把 min 写在 &lt;module-id min=…&gt; 上。
 */
public record ModuleInfo(String id, String version, boolean pack, String name, String vendor, String description,
        List<HierarchyOrder.Dependency> dependencies, List<String> tags, List<Child> children) {

    /** pack 成员（module-pack.xml 钉死的版本）。 */
    public record Child(String id, String version, boolean optional) {
    }

    public static ModuleInfo read(final Path dir, final String id, final String version) throws IOException {
        final Element m = parse(dir.resolve("module.xml"));
        final Properties msg = new Properties();
        load(msg, dir.resolve("message.properties"));
        load(msg, dir.resolve("message_ja.properties"));

        final List<HierarchyOrder.Dependency> deps = new ArrayList<>();
        for (final Element d : descendants(m, "dependency")) {
            final Element mid = child(d, "module-id");
            final Element vv = child(d, "verified-version");
            if (mid == null || vv == null) {
                continue;
            }
            final String verified = vv.getTextContent().trim();
            final String min = attr(vv, "min", attr(mid, "min", verified));
            final String max = attr(vv, "max", attr(mid, "max", "99.99.99"));
            deps.add(new HierarchyOrder.Dependency(mid.getTextContent().trim(), verified, min, max));
        }
        final List<String> tags = new ArrayList<>();
        for (final Element t : descendants(m, "tag")) {
            tags.add(t.getTextContent().trim());
        }
        final Element type = child(m, "type");
        final boolean pack = type != null && "module-pack".equals(type.getTextContent().trim());

        final List<Child> children = new ArrayList<>();
        if (pack) {
            final Element p = parse(dir.resolve("module-pack.xml"));
            for (final Element c : descendants(p, "module")) {
                final Element cid = child(c, "id");
                final Element cv = child(c, "version");
                if (cid != null && cv != null) {
                    children.add(new Child(cid.getTextContent().trim(), cv.getTextContent().trim(), "true".equals(c.getAttribute("optional"))));
                }
            }
        }
        return new ModuleInfo(id, version, pack, text(m, "name", msg), text(m, "vendor", msg), text(m, "description", msg),
                Collections.unmodifiableList(deps), Collections.unmodifiableList(tags), Collections.unmodifiableList(children));
    }

    private static String text(final Element m, final String name, final Properties msg) {
        final Element e = child(m, name);
        if (e == null) {
            return "";
        }
        final StringBuilder sb = new StringBuilder();
        final String s = e.getTextContent().trim();
        int i = 0;
        while (i < s.length()) {
            final int a = s.indexOf("${", i);
            if (a < 0) {
                sb.append(s, i, s.length());
                break;
            }
            final int b = s.indexOf('}', a);
            if (b < 0) {
                sb.append(s, i, s.length());
                break;
            }
            sb.append(s, i, a).append(msg.getProperty(s.substring(a + 2, b), ""));
            i = b + 1;
        }
        return sb.toString();
    }

    private static void load(final Properties p, final Path f) throws IOException {
        if (Files.isRegularFile(f)) {
            try (InputStream in = Files.newInputStream(f)) {
                p.load(in);
            }
        }
    }

    private static Element parse(final Path f) throws IOException {
        try (InputStream in = Files.newInputStream(f)) {
            final DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            dbf.setIgnoringComments(true);
            final Document d = dbf.newDocumentBuilder().parse(in);
            return d.getDocumentElement();
        } catch (final IOException e) {
            throw e;
        } catch (final Exception e) {
            throw new IOException("Cannot read " + f, e);
        }
    }

    private static String attr(final Element e, final String name, final String def) {
        final String v = e.getAttribute(name);
        return v == null || v.isEmpty() ? def : v;
    }

    private static Element child(final Element parent, final String localName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && localName.equals(e.getLocalName())) {
                return e;
            }
        }
        return null;
    }

    private static List<Element> descendants(final Element parent, final String localName) {
        final List<Element> out = new ArrayList<>();
        final var nl = parent.getElementsByTagNameNS("*", localName);
        for (int i = 0; i < nl.getLength(); i++) {
            out.add((Element) nl.item(i));
        }
        return out;
    }
}
