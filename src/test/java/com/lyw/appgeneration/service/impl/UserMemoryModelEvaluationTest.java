package com.lyw.appgeneration.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.ai.memory.ConservativeChatTokenEstimator;
import com.lyw.appgeneration.ai.memory.UserPreferencePromptBuilder;
import com.lyw.appgeneration.config.MemoryTokenProperties;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.lyw.appgeneration.service.impl.UserMemoryState.*;
import static org.junit.jupiter.api.Assertions.*;

/** 使用合成中文对话验证真实模型的对象、范围和最终状态；与固定 JSON 单测独立启用。 */
@EnabledIfEnvironmentVariable(named = "L2_MEMORY_MODEL_EVAL", matches = "true")
class UserMemoryModelEvaluationTest {
    private static ChatModel model;
    private final UserMemoryChangePlannerTest.Store store = new UserMemoryChangePlannerTest.Store();
    private final MemoryTokenProperties properties = new MemoryTokenProperties();
    private final ConservativeChatTokenEstimator estimator = new ConservativeChatTokenEstimator(properties);
    private final UserMemoryOperationParser parser = new UserMemoryOperationParser(new UserPreferenceContract(estimator, properties));

    @BeforeAll static void 连接已配置模型() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        assertNotNull(apiKey, "评测需要开发环境模型密钥");
        model = OpenAiChatModel.builder().baseUrl(System.getenv().getOrDefault("L2_MEMORY_MODEL_URL", "https://api.deepseek.com"))
                .apiKey(apiKey).modelName(System.getenv().getOrDefault("L2_MEMORY_MODEL_NAME", "deepseek-flash"))
                .maxTokens(4096).temperature(0D).timeout(Duration.ofSeconds(60)).maxRetries(0)
                .logRequests(false).logResponses(false).customHeaders(Map.of("Accept-Encoding", "gzip, deflate")).build();
    }

    @Test void 中文日语中文真实抽取保留三版() throws Exception {
        chat("版本1", 10, "记住：以后我所有新应用的页面默认语言都使用简体中文。");
        long first = active("ui.default_language").id();
        chat("版本2", 20, "我改主意了，以后所有新应用的页面默认语言改成日语，替换之前的中文默认。");
        assertTrue(active("ui.default_language").content().contains("日语"));
        long second = active("ui.default_language").id();
        chat("版本3", 30, "再改回来：今后所有新应用的页面默认语言用简体中文，不再默认日语。");
        assertTrue(active("ui.default_language").content().contains("中文"));
        assertNotEquals(first, active("ui.default_language").id());
        assertEquals("SUPERSEDED", store.rows.get(first).status());
        assertEquals("SUPERSEDED", store.rows.get(second).status());
        assertEquals(3, store.rows.values().stream().filter(i -> i.kind() == Kind.FORMAL).count());
    }

    @Test void 正式中文和日语待确认候选独立且明确确认后晋升() throws Exception {
        seed(1, "中文", "ACTIVE", Kind.FORMAL);
        seedVisualPreferences();
        Item before = active("ui.default_language");
        chat("待确认候选", 20, "日语界面我也感兴趣，请先记成一个跨应用的未确认候选。现在别改中文默认，等我确认以后再生效。");
        assertEquals(before, active("ui.default_language"));
        Item candidate = store.rows.values().stream().filter(Item::pending).findFirst().orElseThrow();
        assertTrue(candidate.content().contains("日语"));
        chat("第二回合候选证据", 25, "我仍对那个未确认的日语页面语言候选感兴趣，请继续保留为跨应用候选；我还没有决定启用它，现有默认不变。当前演示页只需一个标题和一个按钮，继续使用单文件 HTML，请给出完整 HTML 代码块。");
        assertEquals(2, store.rows.get(candidate.id()).evidenceCount());
        assertEquals(before, active("ui.default_language"));
        chat("明确确认候选", 30, "确认那个日语候选：从现在起我所有新应用的页面默认语言都改成日语。");
        assertTrue(active("ui.default_language").content().contains("日语"));
        assertEquals("PROMOTED", store.rows.get(candidate.id()).status());
        assertEquals("SUPERSEDED", store.rows.get(1L).status());
    }

    private void seedVisualPreferences() {
        String[][] values = {{"主色", "主色使用紫色 #6d28d9"}, {"卡片圆角", "卡片圆角默认12px"},
                {"正文字号", "正文字号默认18px"}, {"页面背景", "页面背景默认米白色"}};
        for (int index = 0; index < values.length; index++) {
            var attribute = com.lyw.appgeneration.ai.memory.UserPreferenceAttributes.resolve("视觉风格", values[index][0]);
            long id = index + 3L;
            store.rows.put(id, Item.builder().id(id).userId(7).appId(100L).kind(Kind.FORMAL)
                    .category("视觉风格").memoryKey(attribute.key()).attributeName(attribute.name())
                    .content(values[index][1]).status("ACTIVE").evidenceType("EXPLICIT")
                    .evidenceCount(1).legacyEvidenceCount(1).legacyEvidenceTurnId(1L).lastEvidenceTurnId(1).revision(1)
                    .createTime(LocalDateTime.of(2026, 10, 30, 0, 0)).updateTime(LocalDateTime.of(2026, 10, 30, 0, 0)).build());
        }
    }

    @Test void 本应用中文例外不改全局日语() throws Exception {
        seed(1, "日语", "ACTIVE", Kind.FORMAL);
        Item original = active("ui.default_language");
        chat("应用例外", 20, "这个应用是给中国客户看的，这次页面用中文，仅限这个应用，其他新应用继续按原默认。");
        assertEquals(original, active("ui.default_language"));
        assertEquals(1, store.rows.size());
    }

    @Test void 取消候选不改变正式证据() throws Exception {
        seed(1, "中文", "ACTIVE", Kind.FORMAL);
        seed(2, "日语", "CANDIDATE", Kind.CANDIDATE);
        Item original = active("ui.default_language");
        chat("只取消候选", 20, "删掉那个未确认的日语页面语言候选，中文默认保持不变。只取消候选。");
        assertEquals("REVOKED", store.rows.get(2L).status());
        assertEquals(original, active("ui.default_language"));
    }

    @Test void 撤销正式不等于选择相反内容且新要求新建版本() throws Exception {
        seed(1, "中文", "ACTIVE", Kind.FORMAL);
        chat("撤销正式", 20, "忘掉我之前设置的默认页面语言偏好，以后不要套用那条默认。");
        assertEquals("REVOKED", store.rows.get(1L).status());
        assertTrue(store.rows.values().stream().noneMatch(Item::active));
        chat("撤销后重新设置", 30, "重新记住一条全局偏好：今后所有新应用页面默认用简体中文。");
        assertNotEquals(1L, active("ui.default_language").id());
        assertEquals("REVOKED", store.rows.get(1L).status());
    }

    @Test void 同类不同属性可新增超过三条自由内容() throws Exception {
        chat("同类四个属性", 10, "请记住我所有新应用的视觉默认设置：主色用紫色，卡片圆角12px，正文字号18px，页面背景用米白色。四项都是长期偏好。");
        var active = store.rows.values().stream().filter(Item::active).toList();
        assertEquals(4, active.size());
        assertTrue(active.stream().allMatch(i -> i.category().equals("视觉风格")));
        assertTrue(active.stream().anyMatch(i -> i.content().contains("12")));
        assertTrue(active.stream().anyMatch(i -> i.content().contains("米白")));
    }

    @Test void 页面中文与注释日语不是冲突() throws Exception {
        chat("同类不同语言属性", 10, "以后我所有新应用：页面界面用中文，代码注释用日语。请同时记住这两条长期偏好。");
        assertTrue(active("ui.default_language").content().contains("中文"));
        assertTrue(active("code.comment_language").content().contains("日语"));
    }

    @Test void 否定偏好是新增而取消该偏好才是撤销() throws Exception {
        chat("否定偏好", 10, "以后我所有新应用都不要自动播放视频，这是我的长期偏好。");
        Item preference = active("ui.autoplay");
        assertTrue(preference.content().contains("不") || preference.content().contains("禁止"));
        chat("取消否定偏好", 20, "忘掉之前那条不要自动播放视频的长期偏好，撤销这条记忆即可，我没有要求改成自动播放。");
        assertEquals("REVOKED", store.rows.get(preference.id()).status());
        assertTrue(store.rows.values().stream().noneMatch(Item::active));
    }

    private void seed(long id, String value, String status, Kind kind) {
        store.rows.put(id, Item.builder().id(id).userId(7).appId(100L).kind(kind)
                .category("语言偏好").attributeName("页面默认语言").memoryKey("ui.default_language")
                .content("新应用页面默认使用" + value).status(status).evidenceType(kind == Kind.FORMAL ? "EXPLICIT" : "IMPLICIT")
                .evidenceCount(1).legacyEvidenceCount(1).legacyEvidenceTurnId(1L).lastEvidenceTurnId(1).revision(1)
                .createTime(LocalDateTime.of(2026, 10, 30, 0, 0)).updateTime(LocalDateTime.of(2026, 10, 30, 0, 0)).build());
    }
    private Item active(String key) {
        return store.rows.values().stream().filter(i -> i.active() && i.memoryKey().equals(key)).findFirst().orElseThrow();
    }
    private void chat(String name, long turn, String userText) throws Exception {
        Snapshot snapshot = store.load(7);
        Window window = snapshot.windows(estimator, 8192).getFirst();
        String prompt = UserPreferencePromptBuilder.build(window.text(), "turnId=" + turn + "\n用户:" + userText, List.of(turn));
        String output = model.chat(prompt);
        var operations = parser.parse(output, List.of(turn));
        Path report = Path.of(System.getenv().getOrDefault("L2_MEMORY_MODEL_REPORT",
                ".codex/reports/l2-memory/model-evaluation.jsonl"));
        Files.createDirectories(report.getParent());
        Files.writeString(report, new ObjectMapper().writeValueAsString(Map.of("case", name, "userText", userText,
                "output", output, "contractPassed", operations != null)) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        assertNotNull(operations, "真实模型输出未通过契约，详见项目内评测报告");
        var plan = new UserMemoryChangePlanner(store, 7, 100L, snapshot, LocalDateTime.now())
                .plan(operations, snapshot);
        plan.apply(store);
    }
}
