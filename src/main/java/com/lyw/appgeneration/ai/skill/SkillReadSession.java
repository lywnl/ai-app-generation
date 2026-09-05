package com.lyw.appgeneration.ai.skill;

import java.util.Objects;

/** 单个在线工具回合的 Skill 读取配额。 */
public final class SkillReadSession {

    public static final int MAX_BODY_CODE_POINTS = 32_768;
    private String claimedSkillName;

    public synchronized boolean claim(String skillName) {
        Objects.requireNonNull(skillName, "Skill 名称不能为空");
        if (claimedSkillName == null) {
            claimedSkillName = skillName;
            return true;
        }
        return claimedSkillName.equals(skillName);
    }

    public synchronized boolean alreadyClaimed(String skillName) {
        return claimedSkillName != null && claimedSkillName.equals(skillName);
    }
}
