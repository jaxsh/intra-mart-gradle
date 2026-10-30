package io.github.jaxsh.intramart;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * intraMart.resin.home 没设时找本机的 Resin：环境变量 RESIN_HOME → 各盘根目录・Program Files・用户目录下
 * 名字含 resin、且有 lib/resin.jar 的目录。多个时取版本最高的（名字里的 4.0.67 这类）。
 */
final class ResinLocator {

    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)\\.(\\d+)");

    private ResinLocator() {
    }

    static Optional<File> find() {
        final String env = System.getenv("RESIN_HOME");
        if (env != null && isResin(new File(env))) {
            return Optional.of(new File(env));
        }
        final List<File> parents = new ArrayList<>();
        final File[] roots = File.listRoots();
        if (roots != null) {
            parents.addAll(List.of(roots));
        }
        for (final String p : new String[] {System.getenv("ProgramFiles"), System.getenv("ProgramFiles(x86)"),
                System.getProperty("user.home"), "/opt", "/usr/local"}) {
            if (p != null) {
                parents.add(new File(p));
            }
        }
        final List<File> found = new ArrayList<>();
        for (final File parent : parents) {
            final File[] children = parent.listFiles(f -> f.isDirectory() && f.getName().toLowerCase().contains("resin"));
            if (children != null) {
                for (final File c : children) {
                    if (isResin(c)) {
                        found.add(c);
                    }
                }
            }
        }
        return found.stream().max(Comparator.comparingLong(ResinLocator::version));
    }

    private static boolean isResin(final File dir) {
        return new File(dir, "lib/resin.jar").isFile();
    }

    /** resin-pro-4.0.67 → 4_000_067；没有版本号的排最后。 */
    private static long version(final File dir) {
        final Matcher m = VERSION.matcher(dir.getName());
        return m.find() ? Long.parseLong(m.group(1)) * 1_000_000 + Long.parseLong(m.group(2)) * 1_000 + Long.parseLong(m.group(3)) : -1;
    }
}
