package com.lyw.appgeneration.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.ai.memory.UserPreferenceCandidate;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** 校验偏好模型 JSON 契约，并消除同批重复候选的顺序歧义。 */
final class UserPreferenceCandidateParser {

    static final String EXPLICIT = "EXPLICIT";
    static final String IMPLICIT = "IMPLICIT";

    /** 整体非法或同名内容冲突时返回 {@code null}。 */
    List<UserPreferenceCandidate> parse(
            String raw, List<Long> whitelist) {
        if (StrUtil.isBlank(raw)) {
            return null;
        }
        try {
            JSONArray array = JSONUtil.parseArray(raw.trim());
            Set<Long> allowed = Set.copyOf(whitelist);
            Map<String, UserPreferenceCandidate> candidates =
                    new LinkedHashMap<>();
            for (Object value : array) {
                if (!(value instanceof JSONObject item)) {
                    continue;
                }
                UserPreferenceCandidate candidate =
                        toValidatedCandidate(item, allowed);
                if (candidate == null) {
                    continue;
                }
                UserPreferenceCandidate previous =
                        candidates.get(candidate.name());
                UserPreferenceCandidate merged = previous == null
                        ? candidate
                        : mergeDuplicate(previous, candidate);
                if (merged == null) {
                    return null;
                }
                candidates.put(candidate.name(), merged);
            }
            return candidates.values().stream()
                    .sorted(Comparator.comparing(
                            UserPreferenceCandidate::name))
                    .toList();
        } catch (RuntimeException exception) {
            return null;
        }
    }

    static String normalizeContent(String content) {
        return StrUtil.blankToDefault(content, "")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private UserPreferenceCandidate mergeDuplicate(
            UserPreferenceCandidate first,
            UserPreferenceCandidate second) {
        if (!first.content().equals(second.content())) {
            return null;
        }
        String evidenceType = EXPLICIT.equals(first.evidenceType())
                || EXPLICIT.equals(second.evidenceType())
                ? EXPLICIT : IMPLICIT;
        TreeSet<Long> turnIds = new TreeSet<>(first.turnIds());
        turnIds.addAll(second.turnIds());
        return new UserPreferenceCandidate(
                first.name(), first.content(), evidenceType,
                List.copyOf(turnIds));
    }

    private UserPreferenceCandidate toValidatedCandidate(
            JSONObject item, Set<Long> whitelist) {
        String name = StrUtil.trim(item.getStr("name"));
        String content = normalizeContent(item.getStr("content"));
        String evidenceType = item.getStr("evidenceType");
        Object turnIdsValue = item.get("turnIds");
        if (StrUtil.isBlank(name) || StrUtil.isBlank(content)
                || !isSupportedEvidenceType(evidenceType)
                || !(turnIdsValue instanceof JSONArray turnIdsArray)
                || turnIdsArray.isEmpty()) {
            return null;
        }
        TreeSet<Long> turnIds = new TreeSet<>();
        for (Object value : turnIdsArray) {
            Long turnId = toStrictLong(value);
            if (turnId == null || !whitelist.contains(turnId)) {
                return null;
            }
            turnIds.add(turnId);
        }
        if (turnIds.isEmpty()) {
            return null;
        }
        return new UserPreferenceCandidate(
                name, content, evidenceType, List.copyOf(turnIds));
    }

    private Long toStrictLong(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        long longValue = number.longValue();
        return longValue > 0L && number.doubleValue() == (double) longValue
                ? longValue : null;
    }

    private boolean isSupportedEvidenceType(String evidenceType) {
        return EXPLICIT.equals(evidenceType)
                || IMPLICIT.equals(evidenceType);
    }
}
