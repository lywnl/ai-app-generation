package com.lyw.appgeneration.ai.skill;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillReadSessionTest {

    @Test
    void 同一Skill可重复读取但第二个不同Skill被拒绝() {
        SkillReadSession session = new SkillReadSession();

        assertTrue(session.claim("vue-frontend-design"));
        assertTrue(session.alreadyClaimed("vue-frontend-design"));
        assertTrue(session.claim("vue-frontend-design"));
        assertFalse(session.claim("another-skill"));
    }
}
