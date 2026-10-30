package io.github.jaxsh.intramart;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * juggling.im（gzip 的 XML）里拼装要用的部分：最新 structure 的 module-order、选中模块、base 产品。
 * 官方拼 WAR 也只用最新一份 structure。
 */
public final class JugglingProject {

    /** 模块键。pack 表示 module-pack（只展开 .iar）。 */
    public record Module(String id, String version, boolean pack) {
        /** 短 ID：最后一个点之后（web.xml 条件式里 modules[短ID] 用）。 */
        public String shortId() {
            final int i = id.lastIndexOf('.');
            return i < 0 ? id : id.substring(i + 1);
        }
    }

    private final List<Module> moduleOrder;
    private final List<Module> available;
    private final Module base;

    private JugglingProject(final List<Module> moduleOrder, final List<Module> available, final Module base) {
        this.moduleOrder = moduleOrder;
        this.available = available;
        this.base = base;
    }

    /** 展开顺序（juggling.im 的 module-order；与 juggling-core 的 listAvailableModulesOrderByPriority 一致）。 */
    public List<Module> moduleOrder() {
        return moduleOrder;
    }

    /** 选中的模块与 pack（web.xml 条件式里的 modules・versions）。 */
    public List<Module> available() {
        return available;
    }

    /** base 产品（web.xml 模板在它的 imm 根下）。 */
    public Module base() {
        return base;
    }

    public static JugglingProject read(final Path jugglingIm) throws IOException {
        final Document doc;
        try (InputStream in = new GZIPInputStream(Files.newInputStream(jugglingIm))) {
            final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            doc = f.newDocumentBuilder().parse(in);
        } catch (final IOException e) {
            throw e;
        } catch (final Exception e) {
            throw new IOException("Cannot read juggling.im: " + jugglingIm, e);
        }

        final List<Element> structures = children(first(doc.getDocumentElement(), "structures"), "structure");
        if (structures.isEmpty()) {
            throw new IOException("No structure in " + jugglingIm);
        }
        final Element structure = structures.get(structures.size() - 1);
        if (!"true".equals(structure.getAttribute("valid"))) {
            throw new IOException("Latest structure is not valid: " + jugglingIm);
        }

        final List<Module> available = new ArrayList<>();
        final java.util.Set<String> packIds = new java.util.HashSet<>();
        for (final Element e : children(first(structure, "modules"), null)) {
            final boolean pack = "module-pack".equals(e.getLocalName());
            final Module m = new Module(e.getAttribute("id"), e.getAttribute("version"), pack);
            if (pack) {
                packIds.add(key(m));
            }
            if ("true".equals(e.getAttribute("selected"))) {
                available.add(m);
            }
        }

        final List<Module> order = new ArrayList<>();
        for (final Element e : children(first(structure, "module-order"), "module")) {
            final String id = e.getAttribute("id");
            final String version = e.getAttribute("version");
            order.add(new Module(id, version, packIds.contains(id + "@" + version)));
        }

        final Element b = first(structure, "base");
        final Module base = new Module(b.getAttribute("id"), b.getAttribute("version"), true);

        return new JugglingProject(Collections.unmodifiableList(order), Collections.unmodifiableList(available), base);
    }

    private static String key(final Module m) {
        return m.id() + "@" + m.version();
    }

    private static Element first(final Element parent, final String localName) throws IOException {
        for (final Element e : children(parent, localName)) {
            return e;
        }
        throw new IOException("Missing element: " + localName);
    }

    private static List<Element> children(final Element parent, final String localName) {
        final List<Element> list = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element e && (localName == null || localName.equals(e.getLocalName()))) {
                list.add(e);
            }
        }
        return list;
    }
}
