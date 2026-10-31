package com.lyw.appgeneration.ai.memory;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            if (version != L0Snapshot.CURRENT_VERSION) throw new IllegalArgumentException("未知 L0 版本");
            var entries = new ArrayList<L0Snapshot.Message>();
            for (JsonNode message : node.get("messages")) {
                if (!message.isObject() || (message.size() != 3 && message.size() != 4) || !message.path("payload").isObject()) {
                    throw new IllegalArgumentException("L0 消息封装无效");
                }
                if (message.size() == 4 && !message.has("historyRecordId")) throw new IllegalArgumentException("未知 L0 消息来源字段");
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
            JsonNode payloads = MAPPER.readTree(ChatMessageSerializer.messagesToJson(snapshot.messages()));
            for (int index = 0; index < snapshot.entries().size(); index++) {
                L0Snapshot.Message message = snapshot.entries().get(index);
                var entry = messages.addObject();
                entry.put("sequence", message.sequence());
                entry.put("userHistoryId", message.userHistoryId());
                if (message.historyRecordId() != null) entry.put("historyRecordId", message.historyRecordId());
                entry.set("payload", payloads.get(index));
            }
            root.set("turns", MAPPER.valueToTree(snapshot.turns()));
            return MAPPER.writeValueAsString(root);
        } catch (Exception exception) {
            throw new IllegalArgumentException("L0 快照编码失败");
        }
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
