package com.lyw.appgeneration.core.builder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.function.Consumer;

/** 真实 npm／Vite 验收只改变缓存位置；命令、退出码及构建判定仍由生产实现处理。 */
public final class WorkspaceRealVueBuilder {
    private WorkspaceRealVueBuilder() { }

    public static VueProjectBuilder create(Path evidenceRoot, Consumer<BuildResult> observation) throws IOException {
        Files.createDirectories(evidenceRoot);
        Path userConfig = evidenceRoot.resolve("empty-npm-user-config");
        Path globalConfig = evidenceRoot.resolve("empty-npm-global-config");
        Files.writeString(userConfig, "", StandardOpenOption.CREATE_NEW);
        Files.writeString(globalConfig, "", StandardOpenOption.CREATE_NEW);
        Path commands = evidenceRoot.resolve("build-commands.log");
        var process = new ProcessCommandExecutor();
        CommandExecutor isolated = (directory, command, timeout, output, cancellation) -> {
            var actual = new ArrayList<>(command);
            if (command.getFirst().equals("npm")) {
                actual.add("--cache=" + evidenceRoot.resolve("npm-cache"));
                actual.add("--userconfig=" + userConfig);
                actual.add("--globalconfig=" + globalConfig);
            }
            Files.writeString(commands, "cwd=" + directory + "\ncommand=" + actual + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            return process.execute(directory, actual, timeout, output, cancellation);
        };
        return new VueProjectBuilder(isolated, "npm") {
            @Override public BuildResult buildProjectDetailed(Path projectRoot, BuildExecutionContext context) {
                BuildResult result = super.buildProjectDetailed(projectRoot, context);
                observation.accept(result);
                return result;
            }
        };
    }
}
