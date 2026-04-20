package com.lyw.appgeneration.service.rag;

import com.lyw.appgeneration.config.RagProperties;
import com.lyw.appgeneration.service.rag.model.RetrievedSnippet;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Prompt 拼装器:把召回到的模板片段作为"参考模板"前置到用户消息
 * 之所以前置到 userMessage 而非注入 systemPrompt,是因为项目用 @SystemMessage(fromResource=...)
 * 静态加载,动态替换成本高。前置到 user message 是等效且零 prompt 模板改动的方案。
 *
 * @author lyw
 */
@Component
@RequiredArgsConstructor
public class RagPromptAssembler {

    private final RagProperties props;

    private static final String HEADER = """
            ## 参考模板(借鉴风格与实现思路,不要整段照抄;如与用户需求冲突以用户需求为准)
            """;

    private static final String USER_REQUEST_HEADER = """

            ## 用户需求
            """;

    /**
     * 把召回片段与用户原始提示词组装成增强后的 user message
     *
     * @param userMessage 原用户提示词
     * @param snippets    召回片段(可能为空)
     * @return 增强后的 user message;若无召回则原样返回
     */
    public String assemble(String userMessage, List<RetrievedSnippet> snippets) {
        if (snippets == null || snippets.isEmpty()) {
            return userMessage;
        }

        int budget = props.getPrompt().getMaxContextChars();
        StringBuilder sb = new StringBuilder(HEADER);

        int count = 0;
        for (int i = 0; i < snippets.size(); i++) {
            String block = renderSnippet(i + 1, snippets.get(i));
            if (sb.length() + block.length() > budget) {
                break;
            }
            sb.append(block);
            count++;
        }

        if (count == 0) {
            return userMessage;
        }

        sb.append(USER_REQUEST_HEADER).append(userMessage);
        return sb.toString();
    }

    private String renderSnippet(int idx, RetrievedSnippet s) {
        return String.format(
                """

                        ### 参考模板 %d · %s (相似度 %.2f)
                        ```
                        %s
                        ```
                        """,
                idx,
                s.getTitle() == null ? "未命名" : s.getTitle(),
                s.getScore() == null ? 0.0 : s.getScore(),
                s.getCode() == null ? "" : s.getCode()
        );
    }
}
