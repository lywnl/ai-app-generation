package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.ai.memory.UserPreferenceAttributes;
import com.lyw.appgeneration.mapper.AppMemoryCandidateMapper;
import com.lyw.appgeneration.mapper.AppMemoryEvidenceMapper;
import com.lyw.appgeneration.mapper.AppMemoryMapper;
import com.lyw.appgeneration.mapper.AppMemoryEventMapper;
import com.lyw.appgeneration.model.entity.AppMemory;
import com.lyw.appgeneration.model.entity.AppMemoryCandidate;
import com.lyw.appgeneration.model.entity.AppMemoryEvidence;
import com.lyw.appgeneration.model.entity.AppMemoryEvent;
import com.mybatisflex.core.query.QueryColumn;
import com.mybatisflex.core.keygen.KeyGeneratorFactory;
import com.mybatisflex.core.keygen.KeyGenerators;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.lyw.appgeneration.service.impl.UserMemoryState.*;

/** 正式和候选的仓储入口分别绑定物理表；所有写方法由上层批次事务调用。 */
@Component
public class VersionedUserMemoryRepository {
    private static final int PAGE_SIZE = 100;
    private final AppMemoryMapper formal;
    private final AppMemoryCandidateMapper candidates;
    private final AppMemoryEvidenceMapper evidence;
    private final AppMemoryEventMapper events;

    public VersionedUserMemoryRepository(AppMemoryMapper formal,
                                        AppMemoryCandidateMapper candidates,
                                        AppMemoryEvidenceMapper evidence,
                                        AppMemoryEventMapper events) {
        this.formal = Objects.requireNonNull(formal);
        this.candidates = Objects.requireNonNull(candidates);
        this.evidence = Objects.requireNonNull(evidence);
        this.events = Objects.requireNonNull(events);
    }

    long nextId() {
        return Long.parseLong(String.valueOf(KeyGeneratorFactory
                .getKeyGenerator(KeyGenerators.snowFlakeId).generate(null, null)));
    }

    void lockUser(long userId) {
        if (!Objects.equals(formal.lockUser(userId), userId)) {
            throw new IllegalStateException("用户不存在，拒绝写入长期记忆");
        }
    }

    Snapshot load(long userId) {
        return load(userId, false);
    }

    /** 写事务使用当前读，不能让 MySQL 可重复读快照或 MyBatis 一级缓存隐藏新状态。 */
    Snapshot loadCurrent(long userId) {
        return load(userId, true);
    }

    private Snapshot load(long userId, boolean currentRead) {
        return load(userId, currentRead, null);
    }

    Snapshot loadKeyCurrent(long userId, String memoryKey) {
        return load(userId, true, memoryKey);
    }

    private Snapshot load(long userId, boolean currentRead, String memoryKey) {
        List<Item> entries = new ArrayList<>();
        long cursor = 0L;
        while (true) {
            List<AppMemory> page = Objects.requireNonNull(formal.selectListByQuery(
                    stateQuery(userId, cursor, currentRead).eq("memoryKey", memoryKey, memoryKey != null)
                            .in("status", List.of("ACTIVE", "REVOKED"))));
            for (AppMemory item : page) {
                cursor = nextCursor(cursor, item.getId());
                entries.add(from(item));
            }
            if (page.size() < PAGE_SIZE) break;
        }
        cursor = 0L;
        while (true) {
            List<AppMemoryCandidate> page = Objects.requireNonNull(candidates.selectListByQuery(
                    stateQuery(userId, cursor, currentRead).eq("memoryKey", memoryKey, memoryKey != null)
                            .in("status", List.of("CANDIDATE", "REVOKED"))));
            for (AppMemoryCandidate item : page) {
                cursor = nextCursor(cursor, item.getId());
                entries.add(from(item));
            }
            if (page.size() < PAGE_SIZE) break;
        }
        if (entries.stream().anyMatch(item -> item.userId() != userId)) {
            throw new IllegalStateException("仓储返回了其他用户的记忆");
        }
        return Snapshot.of(entries);
    }

    List<AppMemory> activePage(long userId, int page) {
        return Objects.requireNonNull(formal.selectListByQuery(QueryWrapper.create()
                .eq("userId", userId).eq("type", "USER_PREFERENCE").eq("status", "ACTIVE")
                .eq("isDelete", 0).orderBy("evidenceType", true).orderBy("updateTime", false)
                .orderBy("id", false).limit((long) page * PAGE_SIZE, PAGE_SIZE)));
    }

    Map<Long, Evidence> evidence(Item item) {
        Map<Long, Evidence> ids = new LinkedHashMap<>();
        long cursor = 0L;
        while (true) {
            List<AppMemoryEvidence> page = Objects.requireNonNull(evidence.selectListByQuery(
                    QueryWrapper.create().eq("userId", item.userId())
                            .eq("targetKind", item.kind().name()).eq("targetId", item.id())
                            .gt("id", cursor).orderBy("id", true).limit(PAGE_SIZE).forUpdate()));
            for (AppMemoryEvidence row : page) {
                cursor = nextCursor(cursor, row.getId());
                ids.put(row.getTurnId(), new Evidence(row.getTurnId(), row.getAppId(), row.getEvidenceType(),
                        row.getSourceType() == null ? "CHAT" : row.getSourceType()));
            }
            if (page.size() < PAGE_SIZE) break;
        }
        return ids;
    }

    void insert(Item item) {
        requireOne(item.kind() == Kind.FORMAL
                ? formal.insert(toFormal(item)) : candidates.insert(toCandidate(item)));
    }

    void update(Item before, Item after) {
        QueryWrapper expected = QueryWrapper.create().eq("id", before.id())
                .eq("userId", before.userId()).eq("revision", before.revision())
                .eq("status", before.status()).eq("isDelete", 0);
        requireOne(after.kind() == Kind.FORMAL
                ? formal.updateByQuery(toFormal(after), false, expected)
                : candidates.updateByQuery(toCandidate(after), false, expected));
    }

    void addEvidence(Item target, Long appId, long turnId, String evidenceType, LocalDateTime now) {
        addEvidence(target, appId, turnId, evidenceType, "CHAT", now);
    }

    void addEvidence(Item target, Long appId, long turnId, String evidenceType, String sourceType, LocalDateTime now) {
        requireOne(evidence.insert(AppMemoryEvidence.builder().id(nextId())
                .userId(target.userId()).appId(appId).targetKind(target.kind().name())
                .targetId(target.id()).turnId(turnId).evidenceType(evidenceType).sourceType(sourceType)
                .createTime(now).build()));
    }

    List<Item> managedPage(long userId, Kind kind, String category, String keyword, long offset, int size) {
        QueryWrapper query = managementQuery(userId, kind, category, keyword)
                .orderBy("updateTime", false).orderBy("id", false).limit(offset, size);
        return kind == Kind.FORMAL ? formal.selectListByQuery(query).stream().map(VersionedUserMemoryRepository::from).toList()
                : candidates.selectListByQuery(query).stream().map(VersionedUserMemoryRepository::from).toList();
    }

    long managedCount(long userId, Kind kind, String category, String keyword) {
        QueryWrapper query = managementQuery(userId, kind, category, keyword);
        return kind == Kind.FORMAL ? formal.selectCountByQuery(query) : candidates.selectCountByQuery(query);
    }

    private QueryWrapper managementQuery(long userId, Kind kind, String category, String keyword) {
        QueryWrapper query = QueryWrapper.create().eq("userId", userId).eq("type", "USER_PREFERENCE")
                .eq("isDelete", 0).eq("status", kind == Kind.FORMAL ? "ACTIVE" : "CANDIDATE")
                .eq("name", category, category != null);
        if (keyword != null) {
            query.and(new QueryColumn("attributeName").like(keyword).or(new QueryColumn("content").like(keyword)));
        }
        return query;
    }

    Item findActive(long userId, String key) {
        AppMemory row = formal.selectOneByQuery(managementQuery(userId, Kind.FORMAL, null, null).eq("memoryKey", key));
        return row == null ? null : from(row);
    }

    List<Item> activeForKeys(long userId, Set<String> keys) {
        if (keys.isEmpty()) return List.of();
        return formal.selectListByQuery(managementQuery(userId, Kind.FORMAL, null, null)
                .in("memoryKey", keys)).stream().map(VersionedUserMemoryRepository::from).toList();
    }

    Item findCurrentTarget(long userId, Kind kind, long id) {
        QueryWrapper query = QueryWrapper.create().eq("userId", userId).eq("type", "USER_PREFERENCE")
                .eq("isDelete", 0).eq("id", id).forUpdate();
        if (kind == Kind.FORMAL) {
            AppMemory row = formal.selectOneByQuery(query);
            return row == null ? null : from(row);
        }
        AppMemoryCandidate row = candidates.selectOneByQuery(query);
        return row == null ? null : from(row);
    }

    AppMemoryEvent findRequest(long userId, String requestId) { return events.findRequest(userId, requestId); }
    void insertEvent(AppMemoryEvent event) { requireOne(events.insert(event)); }
    long historyCount(long userId) { return events.countHistory(userId); }
    List<AppMemoryEvent> historyPage(long userId, long offset, int size) { return events.historyPage(userId, offset, size); }

    private QueryWrapper pageQuery(long userId, long cursor) {
        return QueryWrapper.create().eq("userId", userId).eq("type", "USER_PREFERENCE")
                .eq("isDelete", 0)
                .gt("id", cursor).orderBy("id", true).limit(PAGE_SIZE);
    }

    private QueryWrapper stateQuery(long userId, long cursor, boolean currentRead) {
        QueryWrapper query = pageQuery(userId, cursor);
        return currentRead ? query.forUpdate() : query;
    }

    private static long nextCursor(long previous, Long id) {
        if (id == null || id <= previous) {
            throw new IllegalStateException("记忆分页游标未严格递增");
        }
        return id;
    }

    private static void requireOne(int affected) {
        if (affected != 1) throw new IllegalStateException("记忆状态已变化或数据库未完成写入");
    }

    private static long number(Long value) { return value == null ? 0L : value; }
    private static int number(Integer value) { return value == null ? 0 : value; }

    static Item from(AppMemory row) {
        return Item.builder().kind(Kind.FORMAL).id(row.getId()).userId(row.getUserId())
                .appId(row.getAppId()).category(row.getName()).memoryKey(row.getMemoryKey())
                .attributeName(row.getAttributeName()).content(row.getContent()).status(row.getStatus())
                .evidenceType(row.getEvidenceType()).evidenceCount(number(row.getEvidenceCount()))
                .lastEvidenceTurnId(number(row.getLastEvidenceTurnId()))
                .legacyEvidenceCount(number(row.getLegacyEvidenceCount()))
                .legacyEvidenceTurnId(row.getLegacyEvidenceTurnId()).revision(number(row.getRevision()))
                .supersededBy(row.getSupersededBy()).originCandidateId(row.getOriginCandidateId())
                .revokedByTurnId(row.getRevokedByTurnId()).createTime(row.getCreateTime())
                .updateTime(row.getUpdateTime()).build();
    }

    static Item from(AppMemoryCandidate row) {
        return Item.builder().kind(Kind.CANDIDATE).id(row.getId()).userId(row.getUserId())
                .appId(row.getAppId()).category(row.getName()).memoryKey(row.getMemoryKey())
                .attributeName(row.getAttributeName()).content(row.getContent()).status(row.getStatus())
                .evidenceType(row.getEvidenceType()).evidenceCount(number(row.getEvidenceCount()))
                .lastEvidenceTurnId(number(row.getLastEvidenceTurnId()))
                .legacyEvidenceCount(number(row.getLegacyEvidenceCount()))
                .legacyEvidenceTurnId(row.getLegacyEvidenceTurnId()).revision(number(row.getRevision()))
                .promotedMemoryId(row.getPromotedMemoryId()).revokedByTurnId(row.getRevokedByTurnId())
                .createTime(row.getCreateTime()).updateTime(row.getUpdateTime())
                .requiresConfirmation(Boolean.TRUE.equals(row.getRequiresConfirmation())).build();
    }

    static AppMemory toFormal(Item item) {
        if (item.kind() != Kind.FORMAL || !Set.of("ACTIVE", "SUPERSEDED", "REVOKED").contains(item.status())) {
            throw new IllegalArgumentException("正式表不接受候选状态");
        }
        return AppMemory.builder().id(item.id()).userId(item.userId()).appId(item.appId())
                .type("USER_PREFERENCE").name(item.category()).memoryKey(item.memoryKey())
                .attributeName(item.attributeName()).content(item.content()).status(item.status())
                .evidenceType(item.evidenceType()).evidenceCount(item.evidenceCount())
                .lastEvidenceTurnId(item.lastEvidenceTurnId()).legacyEvidenceCount(item.legacyEvidenceCount())
                .legacyEvidenceTurnId(item.legacyEvidenceTurnId()).revision(item.revision())
                .supersededBy(item.supersededBy()).originCandidateId(item.originCandidateId())
                .revokedByTurnId(item.revokedByTurnId()).createTime(item.createTime())
                .updateTime(item.updateTime()).isDelete(0).build();
    }

    static AppMemoryCandidate toCandidate(Item item) {
        if (item.kind() != Kind.CANDIDATE || !Set.of("CANDIDATE", "PROMOTED", "REVOKED").contains(item.status())) {
            throw new IllegalArgumentException("候选表不接受正式生效状态");
        }
        return AppMemoryCandidate.builder().id(item.id()).userId(item.userId()).appId(item.appId())
                .type("USER_PREFERENCE").name(item.category()).memoryKey(item.memoryKey())
                .attributeName(item.attributeName()).content(item.content())
                .contentHash(UserPreferenceAttributes.contentHash(item.content())).status(item.status())
                .requiresConfirmation(item.requiresConfirmation())
                .evidenceType(item.evidenceType()).evidenceCount(item.evidenceCount())
                .lastEvidenceTurnId(item.lastEvidenceTurnId()).legacyEvidenceCount(item.legacyEvidenceCount())
                .legacyEvidenceTurnId(item.legacyEvidenceTurnId()).revision(item.revision())
                .promotedMemoryId(item.promotedMemoryId()).revokedByTurnId(item.revokedByTurnId())
                .createTime(item.createTime()).updateTime(item.updateTime()).isDelete(0).build();
    }
}
