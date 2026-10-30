package io.github.jaxsh.intramart;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/** imSetup：平台准备好后，记下这次用的设定（版本・应用 pack・exclude・补丁・数据源…）的指纹。 */
@DisableCachingByDefault(because = "only writes a small stamp file")
public abstract class SetupStampTask extends DefaultTask {

    @Input
    public abstract Property<String> getFingerprint();

    @OutputFile
    public abstract RegularFileProperty getStamp();

    @TaskAction
    public void write() throws IOException {
        final Path p = getStamp().get().getAsFile().toPath();
        Files.createDirectories(p.getParent());
        Files.writeString(p, getFingerprint().get(), StandardCharsets.UTF_8);
        getLogger().lifecycle("intra-mart platform ready ({})", p.getParent());
    }
}
