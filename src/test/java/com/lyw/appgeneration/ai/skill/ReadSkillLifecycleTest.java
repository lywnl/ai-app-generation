package com.lyw.appgeneration.ai.skill;

import com.lyw.appgeneration.ai.VueToolNames;
import com.lyw.appgeneration.ai.tools.*;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ReadSkillLifecycleTest {

    @Test
    void 加载失败保留名额允许同名重试并输出回合配额日志() {
        AtomicInteger reads = new AtomicInteger();
        SkillCatalog catalog = new SkillCatalog(new SkillLoader(new ClassLoader() {
            @Override
            public InputStream getResourceAsStream(String name) {
                if (name.equals("skills/vue-personal-blog/SKILL.md") && reads.incrementAndGet() == 2) {
                    return new ByteArrayInputStream(new byte[]{(byte) 0xc3, 0x28});
                }
                return ReadSkillLifecycleTest.class.getClassLoader().getResourceAsStream(name);
            }
        }));
        var manager = new FileToolExecutionScopeManager(new FileToolBudgetGuard());
        var operations = new AppOperationLeaseManager();
        var sessions = new VueBuildSessionManager();
        Logger logger = (Logger) LoggerFactory.getLogger(ReadSkillTool.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try (var operation = operations.acquire(7L,
                AppOperationLeaseManager.AppOperationType.GENERATE, "retry-turn");
             var lease = sessions.open(operation, 9L, "retry-turn")) {
            var scope = manager.online(lease, "retry-turn", 7L, Set.copyOf(VueToolNames.ONLINE));
            var tool = new ReadSkillTool(catalog, manager);
            assertEquals(SkillToolResult.Status.FAILED,
                    read(manager, scope, tool, "vue-personal-blog").status());
            assertEquals(1, scope.skillReadSession().claimedCount());
            assertEquals(SkillToolResult.Status.APPLIED,
                    read(manager, scope, tool, "vue-online-store").status());
            assertEquals("TOO_MANY_SKILLS",
                    read(manager, scope, tool, "vue-portfolio").failureReason());
            assertEquals(SkillToolResult.Status.APPLIED,
                    read(manager, scope, tool, "vue-personal-blog").status());
            assertEquals(2, scope.skillReadSession().claimedCount());
            String logs = appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                    .reduce("", (left, right) -> left + "\n" + right);
            assertTrue(logs.contains("turnId=retry-turn"));
            assertTrue(logs.contains("claimedCount=2,maxSkills=2,repeated=true"));
            assertTrue(logs.contains("bodySha256="));
            assertFalse(logs.contains(catalog.load("vue-personal-blog").body()));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    @Test
    void 关闭的回合不能重新读取Skill() {
        var manager = new FileToolExecutionScopeManager(new FileToolBudgetGuard());
        var operations = new AppOperationLeaseManager();
        var sessions = new VueBuildSessionManager();
        FileToolExecutionScopeManager.FileToolScope scope;
        try (var operation = operations.acquire(7L,
                AppOperationLeaseManager.AppOperationType.GENERATE, "closed-turn");
             var lease = sessions.open(operation, 9L, "closed-turn")) {
            scope = manager.online(lease, "closed-turn", 7L, Set.copyOf(VueToolNames.ONLINE));
        }
        var tool = new ReadSkillTool(new SkillCatalog(), manager);
        assertThrows(RuntimeException.class, () -> read(manager, scope, tool, "vue-personal-blog"));
        assertEquals(0, scope.skillReadSession().claimedCount());
    }

    private SkillToolResult read(FileToolExecutionScopeManager manager,
            FileToolExecutionScopeManager.FileToolScope scope, ReadSkillTool tool, String name) {
        return SkillToolProtocolSupport.parse(manager.callInScope(scope, "readSkill",
                () -> tool.readSkill(name, scope.appId())));
    }
}
