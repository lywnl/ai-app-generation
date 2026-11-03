package com.lyw.appgeneration.core.handler;

import org.junit.jupiter.api.Test;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

class VueTurnResourcePolicyTest {
    @Test
    void 只读不召回首次修改仍强制() {
        var read = policy(VueTurnMode.READ_ONLY, false, true, true, true);
        assertEquals(VueTurnResourcePolicy.RagScope.NONE, read.ragScope());
        assertFalse(read.prepareImages());
        var mutation = policy(VueTurnMode.MUTATION_REQUIRED, false, true, false, false);
        assertEquals(VueTurnResourcePolicy.RagScope.FULL, mutation.ragScope());
        assertTrue(mutation.prepareImages());
        assertEquals(6, mutation.maxImageThemes());
        assertEquals(Duration.ofSeconds(15), mutation.waitBudget());
    }

    @Test
    void 成功后分别按需且全局RAG开关生效() {
        var rag = policy(VueTurnMode.MUTATION_REQUIRED, true, true, true, false);
        assertEquals(VueTurnResourcePolicy.RagScope.FEATURES, rag.ragScope());
        assertFalse(rag.prepareImages());
        assertEquals(2, rag.maxImageThemes());
        assertEquals(Duration.ofSeconds(8), rag.waitBudget());
        var image = policy(VueTurnMode.MUTATION_REQUIRED, true, true, false, true);
        assertEquals(VueTurnResourcePolicy.RagScope.NONE, image.ragScope());
        assertTrue(image.prepareImages());
        var disabled = policy(VueTurnMode.MUTATION_REQUIRED, false, false, true, true);
        assertEquals(VueTurnResourcePolicy.RagScope.NONE, disabled.ragScope());
        assertTrue(disabled.prepareImages());
    }

    @Test
    void 首次继续包含初始化需求后续聚焦本轮且背景有界() {
        var first = VueTurnResourcePolicy.decide(new VueTurnDecision(VueTurnMode.MUTATION_REQUIRED, true, true),
                false, true, "创建企业官网");
        assertEquals("创建企业官网", first.ragQuery("创建企业官网"));
        assertTrue(first.ragQuery("继续").contains("创建企业官网"));
        var later = VueTurnResourcePolicy.decide(new VueTurnDecision(VueTurnMode.MUTATION_REQUIRED, true, true),
                true, true, "😀".repeat(4000));
        assertEquals("新增新闻页", later.ragQuery("新增新闻页"));
        String prompt = later.imagePlanningPrompt("更换背景图");
        assertTrue(prompt.contains("默认沿用已有 Logo"));
        assertTrue(prompt.indexOf("更换背景图") < prompt.indexOf("初始化背景"));
        assertEquals(2000, prompt.codePoints().filter(c -> c == 0x1f600).count());
    }

    private VueTurnResourcePolicy policy(VueTurnMode mode, boolean success, boolean enabled,
                                         boolean rag, boolean image) {
        return VueTurnResourcePolicy.decide(new VueTurnDecision(mode, rag, image), success, enabled, "");
    }
}
