package com.lyw.appgeneration.service.impl;

import cn.hutool.crypto.digest.DigestUtil;
import com.lyw.appgeneration.ai.memory.ChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.UserMemoryOperation;
import com.lyw.appgeneration.ai.memory.UserPreferenceAttributes;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.model.dto.memory.UserMemoryMutationRequest;
import com.lyw.appgeneration.model.dto.memory.UserMemoryMutationRequest.VersionReference;
import com.lyw.appgeneration.model.dto.memory.UserMemoryPreviewRequest;
import com.lyw.appgeneration.model.dto.memory.UserMemoryQueryRequest;
import com.lyw.appgeneration.model.entity.AppMemoryEvent;
import com.lyw.appgeneration.model.vo.*;
import com.lyw.appgeneration.service.UserMemoryManagementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.lyw.appgeneration.service.impl.UserMemoryState.*;

/** 页面操作和聊天抽取共享状态机、用户锁与缓存失效规则。 */
@Service
@Slf4j
public class UserMemoryManagementServiceImpl implements UserMemoryManagementService {
    private final VersionedUserMemoryRepository repository;
    private final UserMemoryConsistencyCoordinator coordinator;
    private final StringRedisTemplate redis;
    private final UserPreferenceContract contract;
    private final TransactionOperations transactions;
    private final Clock clock;

    @Autowired
    public UserMemoryManagementServiceImpl(VersionedUserMemoryRepository repository,
            UserMemoryConsistencyCoordinator coordinator, StringRedisTemplate redis,
            ChatTokenEstimator estimator, MemoryTokenProperties properties,
            PlatformTransactionManager transactionManager) {
        this(repository, coordinator, redis, estimator, properties,
                new TransactionTemplate(transactionManager), Clock.systemDefaultZone());
    }

    UserMemoryManagementServiceImpl(VersionedUserMemoryRepository repository,
            UserMemoryConsistencyCoordinator coordinator, StringRedisTemplate redis,
            ChatTokenEstimator estimator, MemoryTokenProperties properties,
            TransactionOperations transactions, Clock clock) {
        this.repository = Objects.requireNonNull(repository);
        this.coordinator = Objects.requireNonNull(coordinator);
        this.redis = Objects.requireNonNull(redis);
        this.contract = new UserPreferenceContract(estimator, properties);
        this.transactions = Objects.requireNonNull(transactions);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    @Transactional(readOnly = true)
    public UserMemoryPageVO list(long userId, UserMemoryQueryRequest request) {
        requireUser(userId);
        validatePage(request);
        Kind kind = switch (Objects.toString(request.getStatus(), "")) {
            case "ACTIVE" -> Kind.FORMAL;
            case "CANDIDATE" -> Kind.CANDIDATE;
            default -> throw params("记忆状态无效");
        };
        String category = optional(request.getCategory());
        if (category != null && !UserPreferenceAttributes.CATEGORIES.contains(category)) throw params("记忆类别无效");
        String keyword = optional(request.getKeyword());
        if (keyword != null && keyword.length() > 128) throw params("搜索内容最多 128 个字符");
        List<Item> rows = repository.managedPage(userId, kind, category, keyword,
                offset(request), request.getPageSize());
        requireOwners(userId, rows);
        List<Item> active = kind == Kind.CANDIDATE ? repository.activeForKeys(userId,
                rows.stream().map(Item::memoryKey).collect(Collectors.toSet())) : List.of();
        requireOwners(userId, active);
        Map<String, Item> activeByKey = active.stream().collect(Collectors.toMap(Item::memoryKey, Function.identity()));
        return new UserMemoryPageVO(rows.stream().map(item -> view(item, activeByKey.get(item.memoryKey()))).toList(),
                repository.managedCount(userId, kind, category, keyword), request.getPageNum(), request.getPageSize(),
                repository.managedCount(userId, Kind.FORMAL, null, null),
                repository.managedCount(userId, Kind.CANDIDATE, null, null));
    }

    @Override
    public UserMemoryPreviewVO preview(long userId, UserMemoryPreviewRequest request) {
        requireUser(userId);
        if (request == null) throw params("请提供偏好类别和名称");
        var attribute = resolve(request.category(), request.attributeName());
        Item current = repository.findActive(userId, attribute.key());
        if (current != null) requireOwners(userId, List.of(current));
        return new UserMemoryPreviewVO(request.category(), attribute.name(), activeView(current));
    }

    @Override
    @Transactional(readOnly = true)
    public UserMemoryHistoryVO history(long userId, UserMemoryQueryRequest request) {
        requireUser(userId);
        validatePage(request);
        List<UserMemoryHistoryVO.Entry> entries = repository.historyPage(userId, offset(request), request.getPageSize())
                .stream().map(event -> new UserMemoryHistoryVO.Entry(Long.toString(event.getId()), event.getSourceType(),
                        event.getCreateTime(), UserMemoryEventCodec.readChanges(event.getChangesJson()))).toList();
        return new UserMemoryHistoryVO(entries, repository.historyCount(userId), request.getPageNum(), request.getPageSize());
    }

    @Override
    public UserMemoryMutationVO change(long userId, UserMemoryMutationRequest request) {
        requireUser(userId);
        Command command = normalize(request);
        String hash = DigestUtil.sha256Hex(UserMemoryEventCodec.write(command));
        try (var ignored = coordinator.acquire(userId)) {
            Outcome outcome = transactions.execute(status -> mutate(userId, command, hash));
            if (outcome == null) throw new IllegalStateException("记忆事务未返回结果");
            if (outcome.formalChanged()) {
                for (String prefix : List.of(UserMemoryCacheKeys.CURRENT, UserMemoryCacheKeys.PREVIOUS, UserMemoryCacheKeys.LEGACY)) {
                    try { redis.delete(prefix + userId); }
                    catch (RuntimeException exception) { log.warn("清理记忆缓存失败 userId={} type={}", userId, exception.getClass().getSimpleName()); }
                }
            }
            return outcome.result();
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("页面记忆操作未完成 userId={} type={}", userId, exception.getClass().getSimpleName());
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "记忆操作未完成，请重试");
        }
    }

    private Outcome mutate(long userId, Command command, String hash) {
        repository.lockUser(userId);
        AppMemoryEvent previous = repository.findRequest(userId, command.requestId());
        if (previous != null) {
            if (previous.getUserId() != userId || !hash.equals(previous.getRequestHash())) throw conflict();
            return new Outcome(UserMemoryEventCodec.readResult(previous.getResultJson()), false);
        }
        Kind targetKind = Set.of("ADOPT", "CANCEL").contains(command.action()) ? Kind.CANDIDATE : Kind.FORMAL;
        Item target = command.target() == null ? null
                : repository.findCurrentTarget(userId, targetKind, id(command.target().id()));
        if (command.target() != null) {
            if (target == null || target.userId() != userId) throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "记忆不存在或不可访问");
            if ((!target.active() && !target.pending()) || target.revision() != id(command.target().revision())) throw conflict();
        }
        var attribute = target == null ? resolve(command.category(), command.attributeName())
                : new UserPreferenceAttributes.Attribute(target.memoryKey(), target.attributeName());
        Snapshot snapshot = repository.loadKeyCurrent(userId, attribute.key());
        Item current = snapshot.items().values().stream().filter(Item::active).findFirst().orElse(null);
        if ("ADOPT".equals(command.action()) || ("CREATE".equals(command.action()) && "ACTIVE".equals(command.saveMode()))) {
            requireExpectedActive(current, command.expectedActive());
        }
        String category = target == null ? command.category() : target.category();
        String content = command.content() != null ? command.content() : target == null ? null : target.content();
        if (Set.of("CREATE", "EDIT", "ADOPT").contains(command.action())
                && !contract.isPreferenceWithinBudget(category, attribute.name(), content)) {
            throw params("偏好内容过长或包含不支持的字符，请简化后再保存");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        long eventId = repository.nextId();
        // 页面操作的有序 ID 与聊天回合共用生成器，用来源字段区分，不能被先前的聊天结果倒退。
        long barrier = snapshot.items().values().stream().mapToLong(item -> Math.max(item.lastEvidenceTurnId(),
                item.revokedByTurnId() == null ? 0 : item.revokedByTurnId())).max().orElse(0);
        if (eventId <= barrier) throw conflict();
        boolean candidateOnly = "CREATE".equals(command.action()) && "CANDIDATE".equals(command.saveMode());
        UserMemoryChangePlanner.Plan plan;
        boolean unchanged = ("EDIT".equals(command.action()) || ("CREATE".equals(command.action()) && !candidateOnly))
                && current != null && current.content().equals(content);
        if (unchanged) {
            plan = new UserMemoryChangePlanner.Plan(List.of(), List.of(), now);
        } else {
            UserMemoryOperation operation = operation(command, target, current, snapshot, category, attribute.name(), content, eventId);
            plan = new UserMemoryChangePlanner(repository, userId, null, snapshot, now, "MANUAL", candidateOnly)
                    .plan(List.of(operation), snapshot);
        }
        Item resultItem = plan.changes().stream().map(UserMemoryChangePlanner.Change::after)
                .filter(item -> candidateOnly ? item.pending() : Set.of("REVOKE", "CANCEL").contains(command.action())
                        ? target != null && item.id() == target.id() : item.active()).findFirst()
                .orElse(unchanged ? current : target);
        if (resultItem == null) throw conflict();
        UserMemoryMutationVO result = new UserMemoryMutationVO(!plan.changes().isEmpty(),
                Long.toString(resultItem.id()), resultItem.kind().name());
        if (plan.formalChanged()) redis.delete(UserMemoryCacheKeys.CURRENT + userId);
        plan.apply(repository);
        var changes = UserMemoryEventCodec.changes(plan);
        repository.insertEvent(AppMemoryEvent.builder().id(eventId).userId(userId).sourceType("MANUAL")
                .requestId(command.requestId()).requestHash(hash).changesJson(UserMemoryEventCodec.write(changes))
                .resultJson(UserMemoryEventCodec.write(result)).hasChanges(!changes.isEmpty()).createTime(now).build());
        return new Outcome(result, plan.formalChanged());
    }

    private UserMemoryOperation operation(Command command, Item target, Item current, Snapshot snapshot,
            String category, String attribute, String content, long eventId) {
        var action = UserMemoryOperation.Action.ADD;
        Long targetId = target == null ? null : target.id();
        String evidence = "EXPLICIT";
        switch (command.action()) {
            case "REVOKE", "CANCEL" -> action = UserMemoryOperation.Action.RETRACT;
            case "ADOPT" -> action = UserMemoryOperation.Action.REINFORCE;
            case "EDIT" -> action = UserMemoryOperation.Action.REPLACE;
            case "CREATE" -> {
                if ("CANDIDATE".equals(command.saveMode())) evidence = "IMPLICIT";
                else {
                    Item candidate = snapshot.items().values().stream().filter(item -> item.pending() && item.content().equals(content)).findFirst().orElse(null);
                    if (candidate != null) { targetId = candidate.id(); action = UserMemoryOperation.Action.REINFORCE; }
                    else if (current != null) { targetId = current.id(); action = UserMemoryOperation.Action.REPLACE; }
                }
            }
            default -> throw params("记忆操作无效");
        }
        return new UserMemoryOperation(action, targetId, category, targetId == null ? attribute : null,
                action == UserMemoryOperation.Action.ADD || action == UserMemoryOperation.Action.REPLACE ? content : null,
                UserMemoryOperation.Scope.USER, evidence, List.of(eventId));
    }

    private Command normalize(UserMemoryMutationRequest request) {
        if (request == null || request.getRequestId() == null
                || !request.getRequestId().matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) throw params("操作标识无效");
        String action = request.getAction();
        if (action == null || !Set.of("CREATE", "EDIT", "ADOPT", "REVOKE", "CANCEL").contains(action)) throw params("记忆操作无效");
        String content = request.getContent() == null ? null : UserPreferenceAttributes.normalizeContent(request.getContent());
        if ("CREATE".equals(action)) {
            resolve(request.getCategory(), request.getAttributeName());
            if (!Set.of("ACTIVE", "CANDIDATE").contains(Objects.toString(request.getSaveMode(), ""))
                    || request.getTarget() != null || content == null || content.isEmpty()) throw params("新增记忆参数无效");
            if ("CANDIDATE".equals(request.getSaveMode()) && request.getExpectedActive() != null) throw params("候选操作不能指定正式替换目标");
        } else {
            requireReference(request.getTarget());
            if (request.getCategory() != null || request.getAttributeName() != null || request.getSaveMode() != null) throw params("已有记忆的类别和属性不能变更");
            if ("EDIT".equals(action) ? content == null || content.isEmpty() : content != null) throw params("操作内容无效");
            if (!"ADOPT".equals(action) && request.getExpectedActive() != null) throw params("此操作不接受额外替换目标");
        }
        if (request.getExpectedActive() != null) requireReference(request.getExpectedActive());
        return new Command(UUID.fromString(request.getRequestId()).toString(), action, request.getCategory(),
                request.getAttributeName() == null ? null : request.getAttributeName().strip(), content,
                request.getSaveMode(), request.getTarget(), request.getExpectedActive());
    }

    private static void requireExpectedActive(Item current, VersionReference expected) {
        if (current == null ? expected != null : expected == null || current.id() != id(expected.id())
                || current.revision() != id(expected.revision())) throw conflict();
    }

    private static void requireReference(VersionReference reference) {
        if (reference == null) throw params("请选择要修改的记忆");
        id(reference.id()); id(reference.revision());
    }

    private static long id(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) throw params("记忆标识或版本无效");
        try { return Long.parseLong(value); }
        catch (NumberFormatException exception) { throw params("记忆标识或版本超出范围"); }
    }

    private static UserPreferenceAttributes.Attribute resolve(String category, String name) {
        try { return UserPreferenceAttributes.resolve(category, name); }
        catch (IllegalArgumentException exception) { throw params("请选择类别并填写有效的偏好名称"); }
    }
    private static void validatePage(UserMemoryQueryRequest request) {
        if (request == null || request.getPageNum() < 1 || request.getPageSize() < 1 || request.getPageSize() > 50) throw params("分页参数无效，每页最多 50 条");
    }
    private static long offset(UserMemoryQueryRequest request) { return ((long) request.getPageNum() - 1) * request.getPageSize(); }
    private static String optional(String value) { return value == null || value.isBlank() ? null : value.strip(); }
    private static void requireUser(long userId) { if (userId <= 0) throw new BusinessException(ErrorCode.NOT_LOGIN_ERROR); }
    private static void requireOwners(long userId, List<Item> rows) {
        if (rows.stream().anyMatch(item -> item.userId() != userId)) throw new IllegalStateException("记忆查询归属不匹配");
    }
    private static UserMemoryVO view(Item item, Item active) {
        return new UserMemoryVO(Long.toString(item.id()), Long.toString(item.revision()), item.category(),
                item.attributeName(), item.content(), item.status(), item.requiresConfirmation(),
                item.createTime(), item.updateTime(), activeView(active));
    }
    private static UserMemoryVO.ActiveMemory activeView(Item item) {
        return item == null ? null : new UserMemoryVO.ActiveMemory(Long.toString(item.id()), Long.toString(item.revision()), item.content());
    }
    private static BusinessException params(String message) { return new BusinessException(ErrorCode.PARAMS_ERROR, message); }
    private static BusinessException conflict() { return new BusinessException(ErrorCode.CONFLICT_ERROR); }
    private record Command(String requestId, String action, String category, String attributeName, String content,
                           String saveMode, VersionReference target, VersionReference expectedActive) { }
    private record Outcome(UserMemoryMutationVO result, boolean formalChanged) { }
}
