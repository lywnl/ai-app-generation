package com.lyw.appgeneration.ai.memory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.ai.plan.PlanDefinition;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;

import java.util.ArrayList;
import java.util.List;

/** 仅封装来源元数据，payload 原样沿用 LangChain4j 的消息 JSON 协议。 */
public final class L0SnapshotCodec {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private L0SnapshotCodec() {
    }

    public static L0Snapshot decode(String memoryId, String json) {
        if (json == null) return L0Snapshot.legacy(memoryId, List.of());
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node != null && node.isArray()) {
                return L0Snapshot.legacy(memoryId, ChatMessageDeserializer.messagesFromJson(json));
            }
            if (node == null || !node.isObject() || node.size() != 7
                    || !node.path("messages").isArray() || !node.path("turns").isArray()
                    || !memoryId.equals(string(node, "memoryId"))) {
                throw new IllegalArgumentException("L0 来源封装无效");
            }
            long version = number(node, "formatVersion");
            if (version != 1 && version != L0Snapshot.CURRENT_VERSION) throw new IllegalArgumentException("未知 L0 版本");
            var entries = new ArrayList<L0Snapshot.Entry>();
            for (JsonNode message : node.get("messages")) {
                if (version == 2 && message.isObject() && "PLAN_RESTORE".equals(string(message, "kind"))) {
                    if (message.size() != 5) throw new IllegalArgumentException("计划恢复封装无效");
                    entries.add(new L0Snapshot.PlanRestore(string(message, "restoreId"),
                            MAPPER.treeToValue(message.get("definition"), PlanDefinition.Identity.class),
                            MAPPER.treeToValue(message.get("coverage"), PlanCoverageBoundary.class), string(message, "body")));
                    continue;
                }
                int fields = version == 2 ? 4 : 3;
                if (!message.isObject() || (message.size() != fields && message.size() != fields + 1)
                        || !message.path("payload").isObject()
                        || (version == 2 && !"ORIGINAL".equals(string(message, "kind")))) {
                    throw new IllegalArgumentException("L0 消息封装无效");
                }
                if (message.size() == fields + 1 && !message.has("historyRecordId")) throw new IllegalArgumentException("未知 L0 消息来源字段");
                var payload = ChatMessageDeserializer.messagesFromJson("[" + message.get("payload") + "]");
                if (payload.size() != 1) throw new IllegalArgumentException("L0 原消息无效");
                entries.add(new L0Snapshot.Message(number(message, "sequence"),
                        number(message, "userHistoryId"), payload.getFirst(),
                        !message.hasNonNull("historyRecordId") ? null : number(message, "historyRecordId")));
            }
            var turns = new ArrayList<L0Snapshot.Turn>();
            for (JsonNode turn : node.get("turns")) turns.add(MAPPER.treeToValue(turn, L0Snapshot.Turn.class));
            return new L0Snapshot((int) version, memoryId, string(node, "memoryEpoch"),
                    number(node, "nextSequence"), number(node, "appliedSummaryRevision"), entries, turns);
        } catch (Exception exception) {
            throw new IllegalArgumentException("L0 快照格式或来源元数据无效");
        }
    }

    public static String encode(L0Snapshot snapshot) {
        if (!snapshot.hasKnownSource()) return ChatMessageSerializer.messagesToJson(snapshot.messages());
        try {
            var root = MAPPER.createObjectNode();
            root.put("formatVersion", snapshot.formatVersion());
            root.put("memoryId", snapshot.memoryId());
            root.put("memoryEpoch", snapshot.memoryEpoch());
            root.put("nextSequence", snapshot.nextSequence());
            root.put("appliedSummaryRevision", snapshot.appliedSummaryRevision());
            var messages = root.putArray("messages");
            for (var item : snapshot.window()) {
                var entry = messages.addObject();
                if (item instanceof L0Snapshot.PlanRestore restore) {
                    entry.put("kind", "PLAN_RESTORE");
                    entry.put("restoreId", restore.restoreId());
                    entry.set("definition", MAPPER.valueToTree(restore.definition()));
                    entry.set("coverage", MAPPER.valueToTree(restore.coverage()));
                    entry.put("body", restore.body());
                    continue;
                }
                L0Snapshot.Message message = (L0Snapshot.Message) item;
                if (snapshot.formatVersion() == 2) entry.put("kind", "ORIGINAL");
                entry.put("sequence", message.sequence());
                entry.put("userHistoryId", message.userHistoryId());
                if (message.historyRecordId() != null) entry.put("historyRecordId", message.historyRecordId());
                entry.set("payload", MAPPER.readTree(ChatMessageSerializer.messagesToJson(List.of(message.payload()))).get(0));
            }
            root.set("turns", MAPPER.valueToTree(snapshot.turns()));
            return MAPPER.writeValueAsString(root);
        } catch (Exception exception) {
            throw new IllegalArgumentException("L0 快照编码失败");
        }
    }

    /** 停写回退的纯转换；保留原文身份、epoch 和摘要读取下界。 */
    public static L0Snapshot downgradeToV1(L0Snapshot snapshot) {
        if (!snapshot.hasKnownSource()) throw new IllegalArgumentException("旧数组没有可回退的已知来源");
        return new L0Snapshot(1, snapshot.memoryId(), snapshot.memoryEpoch(), snapshot.nextSequence(),
                snapshot.appliedSummaryRevision(), snapshot.entries(), snapshot.turns());
    }

    private static long number(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
            throw new IllegalArgumentException("L0 缺少整数元数据");
        }
        return value.longValue();
    }

    private static String string(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual()) throw new IllegalArgumentException("L0 缺少身份字段");
        return value.textValue();
    }
}
