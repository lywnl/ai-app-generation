package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.mapper.AppMapper;
import com.lyw.appgeneration.mapper.AppMemoryExtractCursorMapper;
import com.lyw.appgeneration.mapper.AppMemoryMapper;
import com.lyw.appgeneration.mapper.AppMemorySummaryMapper;
import com.lyw.appgeneration.mapper.ChatHistoryMapper;
import com.lyw.appgeneration.service.AppDeletionPersistenceService;
import com.mybatisflex.core.query.QueryWrapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppDeletionPersistenceServiceImpl
        implements AppDeletionPersistenceService {

    private final ChatHistoryMapper chatHistoryMapper;
    private final AppMemorySummaryMapper summaryMapper;
    private final AppMemoryExtractCursorMapper cursorMapper;
    private final AppMemoryMapper memoryMapper;
    private final AppMapper appMapper;

    public AppDeletionPersistenceServiceImpl(
            ChatHistoryMapper chatHistoryMapper,
            AppMemorySummaryMapper summaryMapper,
            AppMemoryExtractCursorMapper cursorMapper,
            AppMemoryMapper memoryMapper,
            AppMapper appMapper) {
        this.chatHistoryMapper = chatHistoryMapper;
        this.summaryMapper = summaryMapper;
        this.cursorMapper = cursorMapper;
        this.memoryMapper = memoryMapper;
        this.appMapper = appMapper;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW,
            rollbackFor = Exception.class)
    public void deleteAppData(Long appId) {
        requirePositiveId(appId, "应用 ID");
        chatHistoryMapper.deleteByQuery(appIdQuery(appId));
        summaryMapper.deleteByQuery(appIdQuery(appId));
        cursorMapper.deleteByQuery(appIdQuery(appId));
        memoryMapper.unlinkAppId(appId);
        appMapper.deleteById(appId);
    }

    private QueryWrapper appIdQuery(Long appId) {
        return QueryWrapper.create().eq("appId", appId);
    }

    private void requirePositiveId(Long id, String name) {
        if (id == null || id <= 0) {
            throw new IllegalArgumentException(name + " 必须为正数");
        }
    }
}
