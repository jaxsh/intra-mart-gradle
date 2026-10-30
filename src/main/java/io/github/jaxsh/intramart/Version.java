package io.github.jaxsh.intramart;

/** 模块版本 major.minor.micro[-qualifier]（如 2.1.2-PATCH_001）。 */
public record Version(int major, int minor, int micro, String qualifier) {

    public static Version parse(final String s) {
        final int dash = s.indexOf('-');
        final String nums = dash < 0 ? s : s.substring(0, dash);
        final String qualifier = dash < 0 ? null : s.substring(dash + 1);
        final String[] p = nums.split("\\.");
        return new Version(num(p, 0), num(p, 1), num(p, 2), qualifier);
    }

    private static int num(final String[] p, final int i) {
        return i < p.length && !p[i].isEmpty() ? Integer.parseInt(p[i]) : 0;
    }
}
