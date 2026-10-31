package com.lyw.appgeneration.ai.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** 用户记录上的独立证据；原始表达、已发生的修改和验证依据分别保存，不作语义推断。 */
public record DecisionEvidence(int formatVersion, long revision, long userHistoryId, String turnId,
                                String collectedEpoch, long collectedThroughSequence, long collectionGeneration,
                                List<Entry> entries) {
    public static final int VERSION = 1;

    public DecisionEvidence {
        if (formatVersion != VERSION || revision < 0 || userHistoryId <= 0 || turnId == null || turnId.isBlank()
                || turnId.length() > 128 || collectedThroughSequence < 0 || collectionGeneration < 0
                || (collectedEpoch == null && collectedThroughSequence != 0)
                || (collectedEpoch != null && (collectedEpoch.isBlank() || collectedEpoch.length() > 128))) {
            throw new IllegalArgumentException("决策证据身份或版本无效");
        }
        entries = List.copyOf(entries);
        var ids = new java.util.HashSet<String>();
        for (var entry : entries) {
            if (!ids.add(entry.sourceId()) || entry.evidenceRevision() < 1 || entry.evidenceRevision() > revision) {
                throw new IllegalArgumentException("决策证据条目重复或修订无效");
            }
        }
    }

    public static DecisionEvidence empty(long userHistoryId, String turnId) {
        return new DecisionEvidence(VERSION, 0, userHistoryId, turnId, null, 0, 0, List.of());
    }

    /** 行锁内合并，迟到采集只补充依据，不能覆盖更完整的条目或倒退采集边界。 */
    public DecisionEvidence merge(List<Entry> additions, String epoch, long through, long generation, int textLimit) {
        var merged = new LinkedHashMap<String, Entry>();
        entries.forEach(entry -> merged.put(entry.sourceId(), entry));
        long nextRevision = Math.addExact(revision, 1);
        boolean changed = false;
        for (Entry addition : additions) {
            Entry old = merged.get(addition.sourceId());
            Entry candidate = old == null ? addition : old.merge(addition);
            if (old == null || !old.equals(candidate.withRevision(old.evidenceRevision()))) {
                merged.put(candidate.sourceId(), candidate.withRevision(nextRevision));
                changed = true;
            }
        }
        String nextEpoch = collectedEpoch;
        long nextThrough = collectedThroughSequence;
        long nextGeneration = collectionGeneration;
        if (epoch != null && generation >= collectionGeneration) {
            if (Objects.equals(epoch, collectedEpoch)) nextThrough = Math.max(nextThrough, through);
            else if (collectedEpoch == null || generation > collectionGeneration) {
                nextEpoch = epoch;
                nextThrough = through;
            } else {
                throw new IllegalStateException("同一采集代次不能替换来源 epoch");
            }
            nextGeneration = generation;
        }
        changed |= !Objects.equals(nextEpoch, collectedEpoch) || nextThrough != collectedThroughSequence
                || nextGeneration != collectionGeneration;
        long size = merged.values().stream().mapToLong(entry -> entry.text().codePointCount(0, entry.text().length())).sum();
        if (size > textLimit) throw new IllegalStateException("决策原文超过本回合转录资源上限");
        return changed ? new DecisionEvidence(VERSION, nextRevision, userHistoryId, turnId,
                nextEpoch, nextThrough, nextGeneration, List.copyOf(merged.values())) : this;
    }

    public enum SourceKind { USER, MODEL_TEXT, PLAN_SUMMARY, PLAN_REASON, PLAN_FILE_PURPOSE }
    public enum ValidationStatus { UNVERIFIED, SUCCEEDED, FAILED, TIMED_OUT }

    public record ChangeFact(String toolRequestId, long generation, long sequence, String path, String operation) {
        public ChangeFact {
            requireId(toolRequestId);
            if (generation <= 0 || sequence <= 0 || path == null || path.isBlank() || path.length() > 512
                    || !List.of("writeFile", "modifyFile", "deleteFile").contains(operation)) {
                throw new IllegalArgumentException("决策实施依据无效");
            }
        }
    }
    public record BuildFact(String toolRequestId, long generation, long sequence, int attempt, ValidationStatus status) {
        public BuildFact {
            requireId(toolRequestId);
            if (generation <= 0 || sequence <= 0 || attempt <= 0 || status == null || status == ValidationStatus.UNVERIFIED) {
                throw new IllegalArgumentException("决策验证依据无效");
            }
        }
    }
    public record Entry(String sourceId, SourceKind kind, String text, long generation,
                         List<String> relatedFiles, List<String> sourceToolIds,
                         List<ChangeFact> implementations, List<BuildFact> verifications, long evidenceRevision) {
        public Entry {
            requireId(sourceId);
            Objects.requireNonNull(kind);
            Objects.requireNonNull(text);
            if (generation < 0 || evidenceRevision < 0 || (kind != SourceKind.USER && text.isBlank())) {
                throw new IllegalArgumentException("决策原文或修订无效");
            }
            relatedFiles = List.copyOf(relatedFiles);
            sourceToolIds = List.copyOf(sourceToolIds);
            implementations = List.copyOf(implementations);
            verifications = List.copyOf(verifications);
        }

        public Entry withRevision(long revision) {
            return new Entry(sourceId, kind, text, generation, relatedFiles, sourceToolIds, implementations, verifications, revision);
        }

        private Entry merge(Entry other) {
            if (kind != other.kind || !text.equals(other.text) || generation != other.generation
                    || !relatedFiles.equals(other.relatedFiles) || !sourceToolIds.equals(other.sourceToolIds)) {
                throw new IllegalStateException("已留存的决策原文及来源关联不可改写");
            }
            return new Entry(sourceId, kind, text, generation, relatedFiles, sourceToolIds,
                    union(implementations, other.implementations, ChangeFact::toolRequestId),
                    union(verifications, other.verifications, BuildFact::toolRequestId), evidenceRevision);
        }

        /** 只说明关联文件的修改证据；不声称整段模型建议都被采用。 */
        public boolean hasLinkedChanges() { return !implementations.isEmpty(); }

        public ValidationStatus validationStatus() {
            if (implementations.isEmpty()) return ValidationStatus.UNVERIFIED;
            ChangeFact lastChange = implementations.stream().max(java.util.Comparator
                    .comparingLong(ChangeFact::generation).thenComparingLong(ChangeFact::sequence)).orElseThrow();
            return verifications.stream().filter(build -> build.generation() > lastChange.generation()
                            || (build.generation() == lastChange.generation() && build.sequence() > lastChange.sequence()))
                    .max(java.util.Comparator.comparingLong(BuildFact::generation).thenComparingLong(BuildFact::sequence))
                    .map(BuildFact::status).orElse(ValidationStatus.UNVERIFIED);
        }
    }

    private static void requireId(String id) {
        if (id == null || id.isBlank() || id.length() > 1024) throw new IllegalArgumentException("决策来源标识无效");
    }

    private static <T> List<T> union(List<T> old, List<T> added, Function<T, String> id) {
        var result = new LinkedHashMap<String, T>();
        old.forEach(value -> result.put(id.apply(value), value));
        for (T value : added) {
            T existing = result.putIfAbsent(id.apply(value), value);
            if (existing != null && !existing.equals(value)) throw new IllegalStateException("同一执行依据不能改写");
        }
        return List.copyOf(result.values());
    }
}
