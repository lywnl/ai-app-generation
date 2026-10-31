package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.mapper.AppMemoryMapper;
import com.lyw.appgeneration.mapper.AppMemoryCandidateMapper;
import com.lyw.appgeneration.mapper.AppMemoryEvidenceMapper;
import com.lyw.appgeneration.mapper.AppMemoryEventMapper;

import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 为调度、缓存测试提供真实仓储入口；候选和证据 Mapper 始终独立。 */
public final class MemoryTestSupport {
    private MemoryTestSupport() { }

    public static VersionedUserMemoryRepository repository(AppMemoryMapper formal) {
        AppMemoryCandidateMapper candidates = mock(AppMemoryCandidateMapper.class);
        AppMemoryEvidenceMapper evidence = mock(AppMemoryEvidenceMapper.class);
        AppMemoryEventMapper events = mock(AppMemoryEventMapper.class);
        when(formal.lockUser(anyLong())).thenAnswer(inv -> inv.getArgument(0));
        when(candidates.selectListByQuery(any())).thenReturn(List.of());
        when(evidence.selectListByQuery(any())).thenReturn(List.of());
        when(candidates.insert(any())).thenReturn(1);
        when(evidence.insert(any())).thenReturn(1);
        when(events.insert(any())).thenReturn(1);
        when(candidates.updateByQuery(any(), eq(false), any())).thenReturn(1);
        when(formal.updateByQuery(any(), eq(false), any())).thenReturn(1);
        return new VersionedUserMemoryRepository(formal, candidates, evidence, events);
    }
}
