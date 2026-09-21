package com.lyw.appgeneration.ai.plan;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import com.lyw.appgeneration.utils.SecureFileAccess;

import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.OpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/** Vue 在线计划的版本化存储、依赖状态和上下文投影。 */
@Component
public final class AppPlanStateManager {

    static final int MAX_HISTORY_SIZE = 20;
    private static final String PLAN_FILE_NAME = ".plan.json";
    private static final String PLAN_FILE_PREFIX = ".plan-";
    private static final String PLAN_FILE_SUFFIX = ".tmp";

    private final ObjectMapper objectMapper;
    private final PlanStoragePathResolver pathResolver;
    private final ConcurrentHashMap<Long, ReentrantLock> locks =
            new ConcurrentHashMap<>();

    public AppPlanStateManager(
            ObjectMapper objectMapper,
            PlanStoragePathResolver pathResolver) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "ObjectMapper 不能为空");
        this.pathResolver = Objects.requireNonNull(
                pathResolver, "计划路径解析器不能为空");
    }

    public Optional<AppPlan> load(long appId) {
        return withLock(appId, () -> read(appId));
    }

    /** 用户查询使用的无副作用快照，不创建目录或重新绑定活动回合。 */
    public Optional<AppPlan> loadReadOnly(long appId) {
        return withLock(appId, () -> pathResolver.withExistingProjectDirectory(appId, directory -> {
            try (var channel = SecureFileAccess.openRegularFile(directory, Path.of(PLAN_FILE_NAME))) {
                AppPlan plan = objectMapper.readValue(Channels.newInputStream(channel), AppPlan.class);
                if (plan == null) {
                    throw new IllegalArgumentException("计划不能为空");
                }
                validatePlanFiles(plan.files());
                return plan;
            } catch (NoSuchFileException missing) {
                throw missing;
            } catch (IOException | IllegalArgumentException exception) {
                throw new IllegalStateException("计划文件读取失败", exception);
            }
        }));
    }

    /** 读取上一轮计划，并在当前受信回合开始时重绑定活动 turnId。 */
    public Optional<AppPlan> loadForTurn(long appId, String turnId) {
        requireTurnId(turnId);
        return withLock(appId, () -> {
            Optional<AppPlan> loaded = read(appId);
            if (loaded.isEmpty() || loaded.get().activeTurnId().equals(turnId)) {
                return loaded;
            }
            AppPlan rebound = loaded.get().rebindActiveTurn(turnId);
            write(appId, rebound);
            return Optional.of(rebound);
        });
    }

    /** 以期望版本和活动回合原子提交计划，防止旧回合覆盖新状态。 */
    public AppPlan save(
            long appId,
            AppPlan plan,
            int expectedVersion,
            String activeTurnId) {
        return save(appId, plan, expectedVersion, activeTurnId, activeTurnId);
    }

    public AppPlan save(
            long appId,
            AppPlan plan,
            int expectedVersion,
            String activeTurnId,
            String expectedActiveTurnId) {
        Objects.requireNonNull(plan, "计划不能为空");
        requireTurnId(activeTurnId);
        if (!activeTurnId.equals(plan.activeTurnId())) {
            throw new IllegalArgumentException("计划活动回合与当前回合不匹配");
        }
        validatePlanFiles(plan.files());
        return withLock(appId, () -> {
            Optional<AppPlan> existing = read(appId);
            if (existing.isPresent()) {
                if (existing.get().version() != expectedVersion) {
                    throw new PlanVersionConflictException(
                            existing.get().version(), expectedVersion);
                }
                if (expectedActiveTurnId != null
                        && !expectedActiveTurnId.equals(existing.get().activeTurnId())) {
                    throw new PlanTurnConflictException(
                            existing.get().activeTurnId(), expectedActiveTurnId);
                }
            } else if (expectedVersion != 0) {
                throw new PlanVersionConflictException(0, expectedVersion);
            }
            AppPlan trimmed = trimHistory(plan);
            write(appId, trimmed);
            return trimmed;
        });
    }

    /** 校验计划内依赖存在、无自依赖且不形成环。 */
    public void validatePlanFiles(List<PlanFile> files) {
        List<PlanFile> checked = files == null ? List.of() : List.copyOf(files);
        Map<String, PlanFile> byPath = new HashMap<>();
        for (PlanFile file : checked) {
            if (byPath.put(file.path(), file) != null) {
                throw new IllegalArgumentException("计划包含重复文件: " + file.path());
            }
        }
        Map<String, VisitState> states = new HashMap<>();
        for (PlanFile file : checked) {
            visitDependencies(file.path(), byPath, states);
        }
    }

    /** 仅显式修订要求重新变更，间接依赖者保留既有可信状态。 */
    public List<PlanFile> resetExplicitlyChangedStates(
            List<PlanFile> files, Set<String> changedPaths) {
        List<PlanFile> checked = files == null ? List.of() : List.copyOf(files);
        Set<String> changed = changedPaths == null ? Set.of() : Set.copyOf(changedPaths);
        return checked.stream()
                .map(file -> changed.contains(file.path())
                        && file.action() != PlanFileAction.KEEP
                        ? new PlanFile(file.path(), file.purpose(), file.action(),
                        file.dependsOn(), PlanFileState.PENDING)
                        : file)
                .toList();
    }

    /** 在已校验的新图上计算检查建议，不修改文件状态，按计划顺序返回。 */
    public List<String> indirectlyAffectedPaths(
            List<PlanFile> files, Set<String> seedPaths) {
        List<PlanFile> checked = files == null ? List.of() : List.copyOf(files);
        Set<String> seeds = seedPaths == null ? Set.of() : Set.copyOf(seedPaths);
        Map<String, List<String>> reverse = new HashMap<>();
        for (PlanFile file : checked) {
            for (String dependency : file.dependsOn()) {
                reverse.computeIfAbsent(dependency, ignored -> new ArrayList<>())
                        .add(file.path());
            }
        }
        Set<String> affected = new HashSet<>(seeds);
        ArrayDeque<String> queue = new ArrayDeque<>(seeds);
        while (!queue.isEmpty()) {
            for (String dependent : reverse.getOrDefault(queue.removeFirst(), List.of())) {
                if (affected.add(dependent)) {
                    queue.addLast(dependent);
                }
            }
        }
        return checked.stream()
                .map(PlanFile::path)
                .filter(path -> affected.contains(path) && !seeds.contains(path))
                .toList();
    }

    private void visitDependencies(
            String path,
            Map<String, PlanFile> byPath,
            Map<String, VisitState> states) {
        VisitState state = states.get(path);
        if (state == VisitState.VISITING) {
            throw new IllegalArgumentException("计划依赖形成循环: " + path);
        }
        if (state == VisitState.VISITED) {
            return;
        }
        states.put(path, VisitState.VISITING);
        PlanFile file = byPath.get(path);
        for (String dependency : file.dependsOn()) {
            if (dependency.equals(path) || !byPath.containsKey(dependency)) {
                throw new IllegalArgumentException(
                        "计划依赖不存在或自引用: " + path + " -> " + dependency);
            }
            visitDependencies(dependency, byPath, states);
        }
        states.put(path, VisitState.VISITED);
    }

    public String toPromptContext(AppPlan plan) {
        Objects.requireNonNull(plan, "计划不能为空");
        List<String> pending = plan.files().stream()
                .filter(file -> file.state() == PlanFileState.PENDING)
                .map(PlanFile::path)
                .toList();
        List<String> touched = plan.files().stream()
                .filter(file -> file.state() == PlanFileState.TOUCHED)
                .map(PlanFile::path)
                .toList();
        String revisions = plan.history().isEmpty() ? "无" : plan.history().stream()
                .skip(Math.max(0, plan.history().size() - 3L))
                .map(change -> "v" + change.version() + ":" + change.reason())
                .reduce((left, right) -> left + "；" + right)
                .orElse("无");
        return "当前计划 version=" + plan.version()
                + "，活动回合=" + plan.activeTurnId()
                + "，状态=" + plan.status()
                + "，目标：" + plan.summary()
                + "；已触达文件：" + (touched.isEmpty() ? "无" : String.join(", ", touched))
                + "；待处理文件：" + (pending.isEmpty() ? "无" : String.join(", ", pending))
                + "；最近修订：" + revisions
                + "。当前项目已有计划，不要再次调用 makePlan。"
                + "需要调整文件范围、动作或依赖，或当前状态为 REPLAN_PENDING 时，先调用 updatePlan；"
                + "仅继续执行现有计划时直接沿用，不要为开始新回合提交无变更的修订。";
    }

    /** 在当前活动回合中记录可信成功变更。调用方应把本方法放入租约提交边界。 */
    public void recordSuccessfulMutation(
            long appId, String turnId, String relativePath) {
        requireTurnId(turnId);
        withLock(appId, () -> {
            Optional<AppPlan> current = read(appId);
            if (current.isEmpty() || !turnId.equals(current.get().activeTurnId())) {
                return null;
            }
            AppPlan plan = current.orElseThrow();
            boolean planned = plan.files().stream()
                    .anyMatch(file -> file.path().equals(relativePath));
            List<PlanFile> files = new ArrayList<>(plan.files().stream()
                    .map(file -> file.path().equals(relativePath)
                            ? new PlanFile(file.path(), file.purpose(), file.action(),
                            file.dependsOn(), PlanFileState.TOUCHED)
                            : file)
                    .toList());
            if (!planned) {
                files.add(new PlanFile(
                        relativePath, "计划外成功变更，等待 updatePlan 解释",
                        PlanFileAction.MODIFY, List.of(), PlanFileState.OUT_OF_PLAN));
            }
            PlanStatus status = plan.status() == PlanStatus.REPLAN_PENDING
                    ? PlanStatus.REPLAN_PENDING
                    : (isReadyToBuild(files, plan.status())
                    ? PlanStatus.READY_TO_BUILD : PlanStatus.PLANNED);
            write(appId, new AppPlan(
                    plan.planId(), turnId, plan.activeTurnId(),
                    plan.version(), plan.round(), plan.mode(), plan.summary(),
                    files, plan.history(), status));
            return null;
        });
    }

    /** 将当前计划标记为需要修订。 */
    public void markReplanPending(long appId, String turnId) {
        updateStatus(appId, turnId, PlanStatus.REPLAN_PENDING);
    }

    /** 清除当前版本的待修订状态，恢复其可执行状态。 */
    public void clearReplanPending(long appId, String turnId) {
        requireTurnId(turnId);
        withLock(appId, () -> {
            AppPlan current = requireActivePlan(appId, turnId);
            if (current.status() != PlanStatus.REPLAN_PENDING) {
                return null;
            }
            PlanStatus status = isReadyToBuild(current.files(), PlanStatus.PLANNED)
                    ? PlanStatus.READY_TO_BUILD : PlanStatus.PLANNED;
            write(appId, withStatus(current, status));
            return null;
        });
    }

    /** 构建成功后写回计划级 BUILT 状态。 */
    public void markBuilt(long appId, String turnId) {
        requireTurnId(turnId);
        withLock(appId, () -> {
            AppPlan current = requireActivePlan(appId, turnId);
            if (!isReadyToBuild(current.files(), current.status())) {
                throw new IllegalStateException("计划尚未满足构建完成条件");
            }
            write(appId, withStatus(current, PlanStatus.BUILT));
            return null;
        });
    }

    public BuildGateDecision beforeBuild(long appId, String turnId) {
        return beforeBuild(appId, turnId, false);
    }

    public BuildGateDecision beforeBuild(long appId, String turnId, boolean replanPending) {
        requireTurnId(turnId);
        return withLock(appId, () -> {
            AppPlan plan = read(appId).orElse(null);
            if (plan != null && !turnId.equals(plan.activeTurnId())) {
                return BuildGateDecision.from(BuildBlockDiagnostic.single(BuildBlockDiagnostic.Reason.TURN_MISMATCH));
            }
            return BuildGateDecision.from(BuildBlockDiagnostic.forPlan(plan, replanPending));
        });
    }

    private boolean isReadyToBuild(List<PlanFile> files, PlanStatus status) {
        if (files.isEmpty() || status == PlanStatus.REPLAN_PENDING) {
            return false;
        }
        return files.stream().allMatch(file ->
                (file.action() == PlanFileAction.KEEP
                        || file.state() == PlanFileState.TOUCHED)
                        && file.dependsOn().stream().allMatch(dependency ->
                        files.stream().filter(candidate ->
                                candidate.path().equals(dependency)).findFirst()
                                .map(prerequisite -> prerequisite.action()
                                        == PlanFileAction.KEEP
                                        || prerequisite.state()
                                        == PlanFileState.TOUCHED)
                                .orElse(false)));
    }

    private AppPlan requireActivePlan(long appId, String turnId) {
        AppPlan current = read(appId).orElseThrow(() ->
                new IllegalStateException("当前项目没有计划"));
        if (!turnId.equals(current.activeTurnId())) {
            throw new PlanTurnConflictException(current.activeTurnId(), turnId);
        }
        return current;
    }

    private void updateStatus(long appId, String turnId, PlanStatus status) {
        requireTurnId(turnId);
        withLock(appId, () -> {
            AppPlan current = requireActivePlan(appId, turnId);
            if (current.status() != status) {
                write(appId, withStatus(current, status));
            }
            return null;
        });
    }

    private AppPlan withStatus(AppPlan plan, PlanStatus status) {
        return new AppPlan(
                plan.planId(), plan.lastModifiedTurnId(), plan.activeTurnId(),
                plan.version(), plan.round(), plan.mode(), plan.summary(),
                plan.files(), plan.history(), status);
    }

    private Optional<AppPlan> read(long appId) {
        return pathResolver.withSecureProjectDirectory(appId, secure -> {
            try (SeekableByteChannel channel = secure.newByteChannel(
                    Path.of(PLAN_FILE_NAME),
                    Set.<OpenOption>of(StandardOpenOption.READ,
                            LinkOption.NOFOLLOW_LINKS))) {
                return Optional.of(objectMapper.readValue(
                        Channels.newInputStream(channel), AppPlan.class));
            } catch (NoSuchFileException missing) {
                return Optional.empty();
            } catch (IOException | RuntimeException exception) {
                if (java.nio.file.Files.isSymbolicLink(pathResolver.planPath(appId))) {
                    return Optional.empty();
                }
                throw new IllegalStateException(
                        "读取计划文件失败: " + pathResolver.planPath(appId), exception);
            }
        });
    }

    private void write(long appId, AppPlan plan) {
        byte[] content;
        try {
            content = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(plan);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("序列化计划失败", exception);
        }
        pathResolver.withSecureProjectDirectory(appId, secure -> {
            String tempName = PLAN_FILE_PREFIX + UUID.randomUUID() + PLAN_FILE_SUFFIX;
            Path temp = Path.of(tempName);
            try {
                try (SeekableByteChannel channel = secure.newByteChannel(
                        temp,
                        Set.<OpenOption>of(StandardOpenOption.CREATE_NEW,
                                StandardOpenOption.WRITE,
                                LinkOption.NOFOLLOW_LINKS))) {
                    Channels.newOutputStream(channel).write(content);
                }
                // SecureDirectoryStream 的同目录 rename 在支持的平台上直接替换目标，
                // 不先删除旧文件，确保移动失败时旧版本仍可读取。
                secure.move(temp, secure, Path.of(PLAN_FILE_NAME));
                return null;
            } catch (IOException exception) {
                try {
                    secure.deleteFile(temp);
                } catch (IOException ignored) {
                    // 保留原始写入失败，临时文件清理失败由目录安全测试暴露。
                }
                throw new IllegalStateException("原子写入计划文件失败", exception);
            }
        });
    }

    private AppPlan trimHistory(AppPlan plan) {
        List<PlanChange> history = plan.history();
        if (history.size() <= MAX_HISTORY_SIZE) {
            return plan;
        }
        return new AppPlan(
                plan.planId(), plan.lastModifiedTurnId(), plan.activeTurnId(),
                plan.version(), plan.round(), plan.mode(), plan.summary(),
                plan.files(),
                new ArrayList<>(history.subList(
                        history.size() - MAX_HISTORY_SIZE, history.size())),
                plan.status());
    }

    private <T> T withLock(long appId, java.util.function.Supplier<T> action) {
        if (appId <= 0) {
            throw new IllegalArgumentException("appId 必须大于 0");
        }
        ReentrantLock lock = locks.computeIfAbsent(appId, ignored -> new ReentrantLock());
        lock.lock();
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    private static void requireTurnId(String turnId) {
        if (turnId == null || turnId.isBlank()) {
            throw new IllegalArgumentException("turnId 不能为空");
        }
    }

    private enum VisitState {
        VISITING,
        VISITED
    }

    public static final class PlanVersionConflictException extends IllegalStateException {

        private final int actualVersion;
        private final int expectedVersion;

        public PlanVersionConflictException(int actualVersion, int expectedVersion) {
            super("计划版本冲突: actual=" + actualVersion
                    + ", expected=" + expectedVersion);
            this.actualVersion = actualVersion;
            this.expectedVersion = expectedVersion;
        }

        public int actualVersion() {
            return actualVersion;
        }

        public int expectedVersion() {
            return expectedVersion;
        }
    }

    public static final class PlanTurnConflictException extends IllegalStateException {

        public PlanTurnConflictException(String actualTurnId, String expectedTurnId) {
            super("计划活动回合冲突: actual=" + actualTurnId
                    + ", expected=" + expectedTurnId);
        }
    }

    public record BuildGateDecision(boolean allowed, String message, BuildBlockDiagnostic diagnostic) {

        public BuildGateDecision(boolean allowed, String message) {
            this(allowed, message, null);
        }

        public static BuildGateDecision from(BuildBlockDiagnostic diagnostic) {
            return new BuildGateDecision(diagnostic.reason() == BuildBlockDiagnostic.Reason.NONE,
                    diagnostic.message(), diagnostic);
        }

        public static BuildGateDecision pass() {
            return new BuildGateDecision(true, "");
        }

        public static BuildGateDecision rejected(String message) {
            return new BuildGateDecision(false, message);
        }
    }
}
