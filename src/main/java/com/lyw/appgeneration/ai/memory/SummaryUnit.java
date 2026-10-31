package com.lyw.appgeneration.ai.memory;

import com.lyw.appgeneration.model.enums.ChatMemoryOutcome;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageSerializer;

import java.util.List;
import java.util.Objects;

/** 统一摘要的不可拆来源单元。终态与决策有独立来源种类，不能冒充原始 AI 消息。 */
public record SummaryUnit(String sourceId, long userHistoryId, Kind kind, String memoryEpoch,
                           long firstSequence, long lastSequence, List<ChatMessage> messages,
                           Long terminalHistoryId, ChatMemoryOutcome terminalOutcome,
                           DecisionEvidence.Entry decision) {
    public SummaryUnit {
        if (sourceId == null || sourceId.isBlank() || userHistoryId <= 0 || firstSequence < 0 || lastSequence < firstSequence) {
            throw new IllegalArgumentException("摘要来源单元身份无效");
        }
        Objects.requireNonNull(kind);
        messages = List.copyOf(messages);
        if ((kind == Kind.TERMINAL) != (terminalHistoryId != null && terminalOutcome != null)
                || (kind == Kind.DECISION) != (decision != null)) throw new IllegalArgumentException("摘要来源类型与资料不符");
    }

    public enum Kind { USER, MODEL_TEXT, TOOL_BATCH, PENDING_TOOL_BATCH, TERMINAL, DECISION }
    public boolean complete() { return kind != Kind.PENDING_TOOL_BATCH; }
    public boolean raw() { return kind != Kind.TERMINAL && kind != Kind.DECISION; }
    public int tokens(ChatTokenEstimator estimator) {
        return decision == null ? estimator.estimateMessages(messages) : estimator.estimateText(decision.text());
    }

    /** JSON 字段中的自由文本始终是引用材料，不会生成新的消息角色。 */
    public String promptMaterial() {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var object = mapper.createObjectNode();
        object.put("来源", sourceId);
        object.put("用户记录", userHistoryId);
        object.put("材料类型", kind.name());
        if (terminalHistoryId != null) {
            object.put("真实终态记录", terminalHistoryId);
            object.put("真实终态", terminalOutcome.name());
        }
        if (decision != null) {
            object.set("原始决策证据", mapper.valueToTree(decision));
            object.put("关联文件有修改依据", decision.hasLinkedChanges());
            object.put("关联修改的验证状态", decision.validationStatus().name());
            object.put("使用边界", "只对所列实施引用成立，不能把整段建议视为均已采用；用户原话引用该用户记录。");
        } else {
            try {
                object.set("引用消息", mapper.readTree(ChatMessageSerializer.messagesToJson(messages)));
            } catch (com.fasterxml.jackson.core.JsonProcessingException exception) {
                throw new IllegalStateException("摘要原消息编码失败");
            }
        }
        return object.toString();
    }
}
