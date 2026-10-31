package com.lyw.appgeneration.service;

import com.lyw.appgeneration.model.entity.ChatHistory;

import java.util.function.Supplier;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

/** 终态编排测试的模块替身；真实证据／摘要事务由存储集成测试验证。 */
public final class MemorySummaryTestSupport {
    private MemorySummaryTestSupport() { }

    public static MemorySummaryService terminalPersistenceStub() {
        var service = mock(MemorySummaryService.class);
        delegateTerminalSave(service);
        return service;
    }

    public static void delegateTerminalSave(MemorySummaryService service) {
        doAnswer(invocation -> invocation.<Supplier<ChatHistory>>getArgument(1).get())
                .when(service).persistTurnOutcome(any(), any());
        org.mockito.Mockito.when(service.readSnapshot(org.mockito.ArgumentMatchers.anyLong())).thenReturn(MemorySummarySnapshot.empty());
    }
}
