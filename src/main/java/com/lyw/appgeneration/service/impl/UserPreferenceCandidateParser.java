package com.lyw.appgeneration.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.ai.memory.UserPreferenceCandidate;
import com.lyw.appgeneration.ai.memory.UserPreferenceValueCatalog;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** 校验偏好模型 JSON 契约，并消除同批重复候选的顺序歧义。 */
final class UserPreferenceCandidateParser {

    static final String EXPLICIT = "EXPLICIT";
    static final String IMPLICIT = "IMPLICIT";

    private final UserPreferenceContract preferenceContract;

    UserPreferenceCandidateParser(
            UserPreferenceContract preferenceContract) {
        this.preferenceContract = Objects.requireNonNull(
                preferenceContract, "偏好契约不能为空");
    }

    /** 整体非法时返回 {@code null}，合法数组无有效候选时返回空列表。 */
    List<UserPreferenceCandidate> parse(
            String raw, List<Long> whitelist) {
        if (StrUtil.isBlank(raw)
                || !preferenceContract.isRawOutputWithinBudget(raw)) {
            return null;
        }
        try {
            JSONArray array = JSONUtil.parseArray(raw.trim());
            if (array.isEmpty()) {
                return List.of();
            }
            Set<Long> allowed = Set.copyOf(whitelist);
            Map<String, UserPreferenceCandidate> candidates =
                    new LinkedHashMap<>();
            Set<String> conflictingNames = new HashSet<>();
            for (Object value : array) {
                if (!(value instanceof JSONObject item)) {
                    continue;
                }
                UserPreferenceCandidate candidate =
                        toValidatedCandidate(item, allowed);
                if (candidate == null) {
                    continue;
                }
                if (conflictingNames.contains(candidate.name())) {
                    continue;
                }
                UserPreferenceCandidate previous =
                        candidates.get(candidate.name());
                UserPreferenceCandidate merged = previous == null
                        ? candidate
                        : mergeDuplicate(previous, candidate);
                if (merged == null) {
                    candidates.remove(candidate.name());
                    conflictingNames.add(candidate.name());
                    continue;
                }
                candidates.put(candidate.name(), merged);
            }
            List<UserPreferenceCandidate> result = candidates.values().stream()
                    .sorted(Comparator.comparing(
                            UserPreferenceCandidate::name))
                    .limit(UserPreferenceContract.MAX_CANDIDATES)
                    .toList();
            return result;
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
        Object nameValue = item.get("name");
        Object valueCodesValue = item.get("valueCodes");
        Object evidenceTypeValue = item.get("evidenceType");
        if (!(nameValue instanceof String nameText)
                || !(valueCodesValue instanceof JSONArray valueCodesArray)
                || !(evidenceTypeValue instanceof String evidenceType)) {
            return null;
        }
        String name = StrUtil.trim(nameText);
        List<String> valueCodes = toStrictStringCodes(valueCodesArray);
        String content = UserPreferenceValueCatalog.render(name, valueCodes);
        Object turnIdsValue = item.get("turnIds");
        if (content == null
                || !preferenceContract.isPreferenceWithinBudget(name, content)
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

    private List<String> toStrictStringCodes(JSONArray valueCodes) {
        List<String> codes = new java.util.ArrayList<>(valueCodes.size());
        for (Object value : valueCodes) {
            if (!(value instanceof String code)) {
                return null;
            }
            codes.add(code);
        }
        return codes;
    }

    private Long toStrictLong(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        try {
            BigDecimal decimal = switch (number) {
                case BigDecimal bigDecimal -> bigDecimal;
                case BigInteger bigInteger -> new BigDecimal(bigInteger);
                case Byte ignored -> BigDecimal.valueOf(number.longValue());
                case Short ignored -> BigDecimal.valueOf(number.longValue());
                case Integer ignored -> BigDecimal.valueOf(number.longValue());
                case Long ignored -> BigDecimal.valueOf(number.longValue());
                default -> new BigDecimal(number.toString());
            };
            long longValue = decimal.longValueExact();
            return longValue > 0L ? longValue : null;
        } catch (ArithmeticException | NumberFormatException exception) {
            return null;
        }
    }

    private boolean isSupportedEvidenceType(String evidenceType) {
        return EXPLICIT.equals(evidenceType)
                || IMPLICIT.equals(evidenceType);
    }
}
