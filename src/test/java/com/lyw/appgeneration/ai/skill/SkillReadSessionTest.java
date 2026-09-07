package com.lyw.appgeneration.ai.skill;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillReadSessionTest {

    @Test
    void 并发同名领取原子区分首次和重读() throws Exception {
        SkillReadSession session = new SkillReadSession();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var results = executor.invokeAll(List.of(
                    () -> session.claimStatus("vue-personal-blog"),
                    () -> session.claimStatus("vue-personal-blog")));
            var statuses = new java.util.HashSet<>();
            for (var result : results) statuses.add(result.get());
            org.junit.jupiter.api.Assertions.assertEquals(
                    java.util.Set.of(SkillReadSession.ClaimStatus.NEW,
                            SkillReadSession.ClaimStatus.REPEATED), statuses);
        }
    }

    @Test
    void 两种Skill可重复读取但第三种被拒绝() {
        SkillReadSession session = new SkillReadSession();

        assertTrue(session.claim("vue-frontend-design"));
        assertTrue(session.alreadyClaimed("vue-frontend-design"));
        assertTrue(session.claim("vue-frontend-design"));
        assertTrue(session.claim("vue-personal-blog"));
        assertFalse(session.claim("vue-online-store"));
        assertTrue(session.claim("vue-frontend-design"));
        assertTrue(session.claim("vue-personal-blog"));
    }

    @Test
    void 不同回合分别领取配额() {
        SkillReadSession first = new SkillReadSession();
        first.claim("vue-frontend-design");
        first.claim("vue-personal-blog");
        assertFalse(first.claim("vue-online-store"));
        assertTrue(new SkillReadSession().claim("vue-online-store"));
    }

    @Test
    void 并发领取只能接受两种不同名称() throws Exception {
        SkillReadSession session = new SkillReadSession();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Boolean>> calls = List.of(
                    () -> session.claim("vue-personal-blog"),
                    () -> session.claim("vue-online-store"),
                    () -> session.claim("vue-portfolio"),
                    () -> session.claim("vue-corporate-website"));
            int accepted = 0;
            for (var result : executor.invokeAll(calls)) {
                if (result.get()) accepted++;
            }
            org.junit.jupiter.api.Assertions.assertEquals(2, accepted);
        }
    }
}
