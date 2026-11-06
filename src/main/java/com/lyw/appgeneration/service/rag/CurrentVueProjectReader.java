package com.lyw.appgeneration.service.rag;

import com.lyw.appgeneration.ai.plan.PlanStoragePathResolver;
import com.lyw.appgeneration.service.rag.model.CurrentVueProject;
import org.springframework.stereotype.Component;

import java.util.function.BooleanSupplier;

/** 只在安全目录句柄内判断工程是否为空，不打开工程文件或跟随目录内链接。 */
@Component
public final class CurrentVueProjectReader {
    private final PlanStoragePathResolver paths;

    public CurrentVueProjectReader(PlanStoragePathResolver paths) { this.paths = paths; }

    public CurrentVueProject read(long appId) { return read(appId, () -> true); }

    public CurrentVueProject read(long appId, BooleanSupplier active) {
        if (!active.getAsBoolean()) return CurrentVueProject.unavailable("DEADLINE_OR_CANCELLED");
        try {
            CurrentVueProject project = paths.withExistingProjectDirectory(appId, directory -> {
                if (!active.getAsBoolean()) return CurrentVueProject.unavailable("DEADLINE_OR_CANCELLED");
                // 计划初始化产生的空目录仍是新工程；任何条目都意味着已有工程。
                boolean hasEntries = directory.iterator().hasNext();
                return new CurrentVueProject(
                        hasEntries ? CurrentVueProject.Status.AVAILABLE : CurrentVueProject.Status.ABSENT,
                        hasEntries ? "NONE" : "EMPTY_PROJECT_DIRECTORY");
            }).orElseGet(() -> new CurrentVueProject(CurrentVueProject.Status.ABSENT, "PROJECT_ABSENT"));
            return active.getAsBoolean() ? project : CurrentVueProject.unavailable("DEADLINE_OR_CANCELLED");
        } catch (RuntimeException unsafe) {
            return CurrentVueProject.unavailable("UNSAFE_PROJECT_PATH");
        }
    }
}
