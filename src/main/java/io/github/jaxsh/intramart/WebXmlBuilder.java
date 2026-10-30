package io.github.jaxsh.intramart;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.w3c.dom.Comment;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import ognl.Ognl;
import ognl.OgnlException;

/**
 * 与官方开源的 @intra-mart/juggling-core 0.1.5（LGPL-3.0） dist/build/web-xml.js 的 composeWebXml 同一规则：
 * base pack imm 根下的 web.xml 模板，&lt;web-app&gt; 的每个子元素前若有 {@code <!--@ 式 -->}，用 OGNL 求值，真则保留。
 * 变量：modules[短ID|ID]（缺省 false）・versions（major.minor.micro）・versionWithPatches（带 -qualifier）・runtimes[runtime]。
 * OGNL 用官方 CUI 同版的 2.7.3。
 */
public final class WebXmlBuilder {

    /** 序列化后的自闭合空元素 <name attr="…"/>（web.xml 的注释里没有这种写法）。 */
    private static final java.util.regex.Pattern EMPTY_ELEMENT =
            java.util.regex.Pattern.compile("<([A-Za-z_][\\w.:-]*)((?:\\s+[\\w.:-]+=\"[^\"]*\")*)\\s*/>");

    private WebXmlBuilder() {
    }

    public static void build(final byte[] template, final JugglingProject project, final String runtime, final Path out)
            throws IOException {
        try {
            final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            f.setIgnoringComments(false);
            f.setValidating(false);
            final Document src = f.newDocumentBuilder().parse(new ByteArrayInputStream(template));
            final Document dst = (Document) src.cloneNode(true);
            final Element root = dst.getDocumentElement();
            while (root.getChildNodes().getLength() > 0) {
                root.removeChild(root.getFirstChild());
            }

            final Map<String, Object> modules = new DefaultMap<>(Boolean.FALSE);
            final Map<String, Object> versions = new LinkedHashMap<>();
            final Map<String, Object> versionWithPatches = new LinkedHashMap<>();
            for (final JugglingProject.Module m : project.available()) {
                modules.put(m.shortId(), Boolean.TRUE);
                modules.put(m.id(), Boolean.TRUE);
                final Version v = Version.parse(m.version());
                final String plain = v.major() + "." + v.minor() + "." + v.micro();
                final String withPatch = v.qualifier() == null || v.qualifier().isEmpty() ? plain : plain + "-" + v.qualifier();
                versions.put(m.shortId(), plain);
                versions.put(m.id(), plain);
                versionWithPatches.put(m.id(), withPatch);
                versionWithPatches.put(m.shortId(), withPatch);
            }
            final Map<String, Object> runtimes = new DefaultMap<>(Boolean.FALSE);
            runtimes.put(runtime, Boolean.TRUE);

            final Map<String, Object> context = new HashMap<>();
            context.put("modules", modules);
            context.put("runtimes", runtimes);
            context.put("versions", versions);
            context.put("versionWithPatches", versionWithPatches);

            final NodeList nodes = src.getDocumentElement().getChildNodes();
            String condition = null;
            for (int i = 0; i < nodes.getLength(); i++) {
                final Node n = nodes.item(i);
                boolean keep;
                switch (n.getNodeType()) {
                case Node.COMMENT_NODE: {
                    if (condition != null) {
                        throw new IllegalStateException("illegal state (CommentNode -> CommentNode@" + i + ")");
                    }
                    final String data = ((Comment) n).getData();
                    if (data.startsWith("@")) {
                        condition = data.substring(1);
                        keep = false;
                    } else {
                        keep = true; // 普通注释原样保留
                    }
                    break;
                }
                case Node.ELEMENT_NODE: {
                    if (condition != null) {
                        final Object r = Ognl.getValue(condition, context);
                        if (!(r instanceof Boolean)) {
                            throw new IOException("illegal expression: " + condition);
                        }
                        keep = (Boolean) r;
                        condition = null;
                    } else {
                        keep = true;
                    }
                    break;
                }
                default:
                    keep = false;
                }
                if (keep) {
                    root.appendChild(dst.createTextNode("\n"));
                    root.appendChild(dst.importNode(n, true));
                }
            }

            // 官方在 Juggling 同梱 JRE 8 上跑，声明里没有 standalone；JDK 9+ 的 Transformer 缺省输出 standalone="no"
            dst.setXmlStandalone(true);
            Files.createDirectories(out.getParent());
            final java.io.StringWriter w = new java.io.StringWriter();
            TransformerFactory.newInstance().newTransformer().transform(new DOMSource(dst), new StreamResult(w));
            // 与官方现行的 @intra-mart/juggling-core 一致：换行全 LF（模板里的 CRLF 原样留下来会混着），
            // 空元素写成 <x></x>（同 juggling-core 的产物；旧 GUI 版 Juggling 是 CRLF 混着与 <x/>）
            final String xml = EMPTY_ELEMENT.matcher(w.toString().replace("\r\n", "\n").replace('\r', '\n'))
                    .replaceAll("<$1$2></$1>");
            Files.writeString(out, xml, java.nio.charset.StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw e;
        } catch (final OgnlException e) {
            throw new IOException("Failed to evaluate a web.xml condition", e);
        } catch (final Exception e) {
            throw new IOException("Failed to generate web.xml", e);
        }
    }

    /** juggling-core 的 MapWithDefault：没有的键返回缺省值。 */
    static final class DefaultMap<K, V> extends LinkedHashMap<K, V> {
        private static final long serialVersionUID = 1L;
        private final V defaultValue;

        DefaultMap(final V defaultValue) {
            this.defaultValue = defaultValue;
        }

        @Override
        public V get(final Object key) {
            return containsKey(key) ? super.get(key) : defaultValue;
        }
    }
}
