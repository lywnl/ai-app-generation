package com.lyw.appgeneration.ai.skill;

import java.util.Objects;
import java.util.LinkedHashSet;
import java.util.Set;

/** 单个在线工具回合的 Skill 读取配额。 */
public final class SkillReadSession {

    public static final int MAX_BODY_CODE_POINTS = 32_768;
    public static final int MAX_DISTINCT_SKILLS = 2;
    private final Set<String> claimedSkillNames = new LinkedHashSet<>();

    public synchronized boolean claim(String skillName) {
        return claimStatus(skillName) != ClaimStatus.REJECTED;
    }

    public synchronized ClaimStatus claimStatus(String skillName) {
        Objects.requireNonNull(skillName, "Skill 名称不能为空");
        if (claimedSkillNames.contains(skillName)) {
            return ClaimStatus.REPEATED;
        }
        if (claimedSkillNames.size() >= MAX_DISTINCT_SKILLS) {
            return ClaimStatus.REJECTED;
        }
        claimedSkillNames.add(skillName);
        return ClaimStatus.NEW;
    }

    public synchronized boolean alreadyClaimed(String skillName) {
        return claimedSkillNames.contains(skillName);
    }

    public synchronized int claimedCount() {
        return claimedSkillNames.size();
    }

    public enum ClaimStatus {
        NEW, REPEATED, REJECTED
    }
}
