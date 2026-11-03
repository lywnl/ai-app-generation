package com.lyw.appgeneration.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class VueTurnRoutingComparisonRunnerTest {
    private List<VueTurnRoutingComparisonRunner.Sample> samples() throws Exception {
        try (var input = getClass().getResourceAsStream("/vue-turn-routing/cases.json")) {
            return new ObjectMapper().readValue(input, new TypeReference<>() { });
        }
    }

    @Test
    void 固定六十条各两轮总计二百四十次且分别使用真实提示词协议() throws Exception {
        var samples = samples();
        AtomicInteger calls = new AtomicInteger();
        ChatModel stub = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                calls.incrementAndGet();
                String user = ((UserMessage) request.messages().getLast()).singleText();
                var sample = samples.stream().filter(s -> user.contains(s.input())).findFirst().orElseThrow();
                String system = ((SystemMessage) request.messages().getFirst()).text();
                boolean joint = system.contains("needImageSearch");
                String response = joint ? "{\"mode\":\"" + sample.mode() + "\",\"needRag\":"
                        + sample.needRag() + ",\"needImageSearch\":" + sample.needImageSearch() + "}"
                        : sample.mode().name();
                return ChatResponse.builder().aiMessage(AiMessage.from(response)).metadata(
                        ChatResponseMetadata.builder().modelName("fixed-stub").build()).build();
            }
        };
        var rows = VueTurnRoutingComparisonRunner.evaluate(stub, samples, ignored -> { });
        assertEquals(240, calls.get());
        assertEquals(120, rows.stream().filter(r -> r.variant().equals("joint")).count());
        assertEquals(true, VueTurnRoutingComparisonRunner.summarize(samples, rows).get("passed"));
    }

    @Test
    void 网络失败不重试不补跑且不完整结果不算通过() throws Exception {
        var samples = samples();
        AtomicInteger calls = new AtomicInteger();
        ChatModel stub = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                calls.incrementAndGet();
                throw new IllegalStateException("账户不可用");
            }
        };
        var rows = VueTurnRoutingComparisonRunner.evaluate(stub, samples, ignored -> { });
        assertEquals(1, calls.get());
        assertEquals(false, VueTurnRoutingComparisonRunner.summarize(samples, rows).get("passed"));
    }
}
