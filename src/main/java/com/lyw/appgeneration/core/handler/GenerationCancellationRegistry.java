package com.lyw.appgeneration.core.handler;

import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** 按应用和生成任务 ID 精确管理活跃回合，防止迟到取消误伤新回合。 */
@Component
public final class GenerationCancellationRegistry {

    private final ConcurrentMap<Key, Entry> entries = new ConcurrentHashMap<>();

    public boolean register(long appId, String generationId, long userId,
                            Runnable cancellationAction) {
        Key key = key(appId, generationId);
        Entry entry = new Entry(userId,
                Objects.requireNonNull(cancellationAction, "取消动作不能为空"));
        return entries.putIfAbsent(key, entry) == null;
    }

    public CancellationResult cancel(long appId, String generationId,
                                    long userId) {
        Entry entry = entries.get(key(appId, generationId));
        if (entry == null) {
            return CancellationResult.NOT_FOUND;
        }
        if (entry.userId() != userId) {
            return CancellationResult.FORBIDDEN;
        }
        entry.cancellationAction().run();
        return CancellationResult.REQUESTED;
    }

    public void unregister(long appId, String generationId,
                           Runnable cancellationAction) {
        entries.computeIfPresent(key(appId, generationId),
                (ignored, entry) -> entry.cancellationAction() == cancellationAction
                        ? null : entry);
    }

    public int size() {
        return entries.size();
    }

    private Key key(long appId, String generationId) {
        if (appId <= 0L) {
            throw new IllegalArgumentException("应用 ID 必须大于 0");
        }
        if (generationId == null || generationId.isBlank()) {
            throw new IllegalArgumentException("生成任务 ID 不能为空");
        }
        return new Key(appId, generationId);
    }

    public enum CancellationResult {
        REQUESTED, NOT_FOUND, FORBIDDEN
    }

    private record Key(long appId, String generationId) {
    }

    private record Entry(long userId, Runnable cancellationAction) {
        private Entry {
            Objects.requireNonNull(cancellationAction, "取消动作不能为空");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Entry entry
                    && entry.cancellationAction() == cancellationAction;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(cancellationAction);
        }
    }
}
