package com.lyw.appgeneration.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lyw.appgeneration.model.vo.UserMemoryHistoryVO.Change;
import com.lyw.appgeneration.model.vo.UserMemoryMutationVO;

import java.util.ArrayList;
import java.util.List;

/** 只将实际状态变化写入历史，不把补充证据误报为修改偏好。 */
final class UserMemoryEventCodec {
    private static final ObjectMapper JSON = new ObjectMapper();
    private UserMemoryEventCodec() { }

    static List<Change> changes(UserMemoryChangePlanner.Plan plan) {
        List<Change> events = new ArrayList<>();
        for (var change : plan.changes()) {
            var item = change.after();
            String action = null;
            String before = null;
            String after = null;
            if (item.active() && change.before() == null) {
                var predecessor = plan.changes().stream().filter(other -> other.before() != null
                        && other.before().active() && Long.valueOf(item.id()).equals(other.after().supersededBy()))
                        .findFirst().orElse(null);
                before = predecessor == null ? null : predecessor.before().content();
                after = item.content();
                action = item.originCandidateId() != null ? "ADOPT" : predecessor == null ? "CREATE" : "EDIT";
            } else if (change.before() != null && change.before().active() && item.revoked()) {
                action = "REVOKE";
                before = item.content();
            } else if (item.pending() && change.before() == null) {
                action = "CANDIDATE_CREATE";
                after = item.content();
            } else if (change.before() != null && change.before().pending() && item.revoked()) {
                action = "CANCEL";
                before = item.content();
            }
            if (action != null) events.add(new Change(action, item.category(), item.attributeName(), before,
                    after, Long.toString(item.id()), item.kind().name(), item.status()));
        }
        return List.copyOf(events);
    }

    static String write(Object value) {
        try { return JSON.writeValueAsString(value); }
        catch (Exception exception) { throw new IllegalStateException("记忆操作记录编码失败", exception); }
    }

    static List<Change> readChanges(String value) {
        try { return JSON.readValue(value, new TypeReference<>() { }); }
        catch (Exception exception) { throw new IllegalStateException("记忆历史记录格式无效"); }
    }

    static UserMemoryMutationVO readResult(String value) {
        try { return JSON.readValue(value, UserMemoryMutationVO.class); }
        catch (Exception exception) { throw new IllegalStateException("记忆操作结果格式无效"); }
    }
}
