package io.github.jaxsh.intramart;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

/**
 * 官方仓库的版本索引：&lt;base|app&gt;/metadata.zip 里的 index.xml（模块 ID → 全部版本），IM-Juggling 找补丁用的同一份
 * （juggling-core 找补丁读的同一份索引）。
 */
public final class RepositoryIndex {

    private static final Pattern PATCH = Pattern.compile("-PATCH_(\\d+)$");

    private final Map<String, Set<String>> versions;

    private RepositoryIndex(final Map<String, Set<String>> versions) {
        this.versions = versions;
    }

    public static RepositoryIndex load(final List<Path> metadataZips) throws IOException {
        final Map<String, Set<String>> v = new HashMap<>();
        for (final Path zip : metadataZips) {
            try (ZipInputStream z = new ZipInputStream(Files.newInputStream(zip))) {
                for (ZipEntry e; (e = z.getNextEntry()) != null;) {
                    if ("index.xml".equals(e.getName())) {
                        read(z, v);
                    }
                }
            }
        }
        return new RepositoryIndex(v);
    }

    /**
     * 补丁（juggling-core dist/model/version-util.js 的 isPatchTarget）：候选是补丁版，major.minor.micro 与当前相同，当前无后缀或补丁号更小；取补丁号最大的。
     * 没有补丁回 null。
     */
    public String latestPatch(final String id, final String current) {
        final Version cur = Version.parse(current);
        final int curNo = patchNo(current);
        String best = null;
        for (final String cand : versions.getOrDefault(id, Set.of())) {
            final int no = patchNo(cand);
            if (no < 0) {
                continue;
            }
            final Version c = Version.parse(cand);
            if (c.major() != cur.major() || c.minor() != cur.minor() || c.micro() != cur.micro()) {
                continue;
            }
            if (no > curNo && (best == null || no > patchNo(best))) {
                best = cand;
            }
        }
        return best;
    }

    /** -PATCH_n 的 n；不是补丁版回 -1（当前版本无后缀时按 0 比）。 */
    static int patchNo(final String v) {
        final Matcher m = PATCH.matcher(v);
        if (m.find()) {
            return Integer.parseInt(m.group(1));
        }
        return v.contains("-") ? -1 : 0;
    }

    private static void read(final InputStream in, final Map<String, Set<String>> out) throws IOException {
        final Element root;
        try {
            final DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(true);
            // ZipInputStream 不能让解析器关掉
            root = f.newDocumentBuilder().parse(new java.io.FilterInputStream(in) {
                @Override
                public void close() {
                }
            }).getDocumentElement();
        } catch (final Exception e) {
            throw new IOException("Cannot read index.xml in metadata.zip", e);
        }
        final var mods = root.getElementsByTagNameNS("*", "module");
        for (int i = 0; i < mods.getLength(); i++) {
            final Element m = (Element) mods.item(i);
            final Set<String> vs = out.computeIfAbsent(m.getAttribute("id"), k -> new LinkedHashSet<>());
            final List<Element> vl = new ArrayList<>();
            final var nl = m.getElementsByTagNameNS("*", "version");
            for (int j = 0; j < nl.getLength(); j++) {
                vl.add((Element) nl.item(j));
            }
            for (final Element ve : vl) {
                for (Node n = ve.getFirstChild(); n != null; n = n.getNextSibling()) {
                    if (n.getNodeType() == Node.TEXT_NODE) {
                        vs.add(n.getNodeValue().trim());
                    }
                }
            }
        }
    }
}
