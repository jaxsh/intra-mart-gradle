package io.github.jaxsh.intramart;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/**
 * 写 juggling.im（gzip 的 XML，IM-Juggling 工程文件；拼 WAR 时原样复制进 WEB-INF，intra-mart 运行时也读）。
 * 单一 structure：modules（全部模块，容器顺序，selected）・base・applications・user-modules・module-order。
 * configurations／documents 留空（拼 WAR 与运行时不读，GUI 用）。
 */
public final class JugglingImWriter {

    private JugglingImWriter() {
    }

    public static String xml(final PlatformResolver.Result r, final String description) {
        final StringBuilder sb = new StringBuilder(1 << 20);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n");
        sb.append("<ns2:juggling xmlns:ns2=\"http://www.intra-mart.jp/juggling\">\n");
        sb.append("    <ns2:structures>\n");
        // 日期取 base 产品的发布日（不用当前时间）：同样的配置每次生成字节相同，后面的 imLibraries 保持 UP-TO-DATE、imWebapp 不重新展开
        final PlatformResolver.Product bp = r.products().get(r.base().id() + " " + r.base().version());
        final String date = bp == null || bp.date().isEmpty() ? "2000-01-01T00:00:00.000+09:00"
                : bp.date().length() == 19 ? bp.date() + ".000+09:00" : bp.date();
        sb.append("        <ns2:structure date=\"").append(esc(date)).append("\" locale=\"ja_JP\" valid=\"true\">\n");
        sb.append("            <ns2:description>").append(esc(description)).append("</ns2:description>\n");
        sb.append("            <ns2:modules>\n");
        for (final ModuleInfo m : PlatformResolver.ContainerOrder.order(r.all())) {
            final String tag = m.pack() ? "module-pack" : "module";
            sb.append("                <ns2:").append(tag).append(" selected=\"").append(r.selected().contains(m.id()))
                    .append("\" id=\"").append(esc(m.id())).append("\" version=\"").append(esc(m.version())).append("\">\n");
            common(sb, m, 20);
            if (m.pack()) {
                children(sb, m, 20);
            }
            sb.append("                </ns2:").append(tag).append(">\n");
        }
        sb.append("            </ns2:modules>\n");
        product(sb, "base", r.base(), r, 12);
        sb.append("            <ns2:applications>\n");
        for (final ModuleInfo a : r.applications()) {
            product(sb, "application", a, r, 16);
        }
        sb.append("            </ns2:applications>\n");
        sb.append("            <ns2:user-modules/>\n");
        sb.append("            <ns2:module-order>\n");
        for (final ModuleInfo m : r.moduleOrder()) {
            sb.append("                <ns2:module id=\"").append(esc(m.id())).append("\" version=\"").append(esc(m.version())).append("\"/>\n");
        }
        sb.append("            </ns2:module-order>\n");
        sb.append("        </ns2:structure>\n");
        sb.append("    </ns2:structures>\n");
        sb.append("</ns2:juggling>\n");
        return sb.toString();
    }

    public static void write(final PlatformResolver.Result r, final String description, final Path out) throws IOException {
        Files.createDirectories(out.getParent());
        try (OutputStream o = new GZIPOutputStream(Files.newOutputStream(out))) {
            o.write(xml(r, description).getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void common(final StringBuilder sb, final ModuleInfo m, final int n) {
        final String i = " ".repeat(n);
        sb.append(i).append("<ns2:name>").append(esc(m.name())).append("</ns2:name>\n");
        sb.append(i).append("<ns2:vendor>").append(esc(m.vendor())).append("</ns2:vendor>\n");
        sb.append(i).append("<ns2:description>").append(esc(m.description())).append("</ns2:description>\n");
        if (m.dependencies().isEmpty()) {
            sb.append(i).append("<ns2:dependencies/>\n");
        } else {
            sb.append(i).append("<ns2:dependencies>\n");
            for (final HierarchyOrder.Dependency d : m.dependencies()) {
                sb.append(i).append("    <ns2:dependency verified-max-version=\"").append(esc(d.max()))
                        .append("\" verified-min-version=\"").append(esc(d.min())).append("\" id=\"").append(esc(d.moduleId()))
                        .append("\" version=\"").append(esc(d.verified())).append("\"/>\n");
            }
            sb.append(i).append("</ns2:dependencies>\n");
        }
        if (m.tags().isEmpty()) {
            sb.append(i).append("<ns2:tags/>\n");
        } else {
            sb.append(i).append("<ns2:tags>\n");
            for (final String t : m.tags()) {
                sb.append(i).append("    <ns2:tag>").append(esc(t)).append("</ns2:tag>\n");
            }
            sb.append(i).append("</ns2:tags>\n");
        }
        sb.append(i).append("<ns2:configurations/>\n");
        sb.append(i).append("<ns2:documents/>\n");
    }

    private static void children(final StringBuilder sb, final ModuleInfo m, final int n) {
        final String i = " ".repeat(n);
        sb.append(i).append("<ns2:children>\n");
        for (final ModuleInfo.Child c : m.children()) {
            sb.append(i).append("    <ns2:reference optional=\"").append(c.optional()).append("\" id=\"").append(esc(c.id()))
                    .append("\" version=\"").append(esc(c.version())).append("\"/>\n");
        }
        sb.append(i).append("</ns2:children>\n");
    }

    private static void product(final StringBuilder sb, final String tag, final ModuleInfo m, final PlatformResolver.Result r, final int n) {
        final String i = " ".repeat(n);
        final PlatformResolver.Product p = r.products().get(m.id() + " " + m.version());
        final String category = p == null ? m.id() + ".category" : p.category();
        final String date = p == null || p.date().isEmpty() ? "" : p.date().length() == 19 ? p.date() + ".000+09:00" : p.date();
        sb.append(i).append("<ns2:").append(tag).append(" category=\"").append(esc(category)).append('"');
        if (!date.isEmpty()) {
            sb.append(" date=\"").append(esc(date)).append('"');
        }
        sb.append(" reason=\"\" status=\"available\" selected=\"true\" id=\"").append(esc(m.id())).append("\" version=\"")
                .append(esc(m.version())).append("\">\n");
        common(sb, m, n + 4);
        children(sb, m, n + 4);
        final List<List<String[]>> req = p == null ? List.of() : p.requiredProducts();
        if (req.isEmpty()) {
            sb.append(i).append("    <required-products/>\n");
        } else {
            sb.append(i).append("    <required-products>\n");
            for (final List<String[]> g : req) {
                sb.append(i).append("        <required-product>\n");
                for (final String[] x : g) {
                    sb.append(i).append("            <product id=\"").append(esc(x[0])).append("\" version=\"").append(esc(x[1])).append("\"/>\n");
                }
                sb.append(i).append("        </required-product>\n");
            }
            sb.append(i).append("    </required-products>\n");
        }
        sb.append(i).append("</ns2:").append(tag).append(">\n");
    }

    private static String esc(final String s) {
        // CR 写成 &#xD;（同官方：模块说明里有 \r\n 的，XML 解析时原始 CR 会被规整掉）
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("\r", "&#xD;");
    }
}
