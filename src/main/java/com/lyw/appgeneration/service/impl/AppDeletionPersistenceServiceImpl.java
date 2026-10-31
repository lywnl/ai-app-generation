package com.lyw.appgeneration.service.impl;

import com.lyw.appgeneration.mapper.AppMapper;
import com.lyw.appgeneration.mapper.AppMemoryExtractCursorMapper;
import com.lyw.appgeneration.mapper.AppMemoryMapper;
import com.lyw.appgeneration.mapper.AppMemoryCandidateMapper;
import com.lyw.appgeneration.mapper.AppMemoryEvidenceMapper;
import com.lyw.appgeneration.mapper.AppMemoryEventMapper;
import com.lyw.appgeneration.mapper.AppMemorySummaryMapper;
import com.lyw.appgeneration.mapper.ChatHistoryMapper;
import com.lyw.appgeneration.service.AppDeletionPersistenceService;
import com.lyw.appgeneration.service.GoodAppCacheService;
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
    private final AppMemoryCandidateMapper candidateMapper;
    private final AppMemoryEvidenceMapper evidenceMapper;
    private final AppMemoryEventMapper eventMapper;
    private final AppMapper appMapper;
    private final GoodAppCacheService goodAppCacheService;

    public AppDeletionPersistenceServiceImpl(
            ChatHistoryMapper chatHistoryMapper,
            AppMemorySummaryMapper summaryMapper,
            AppMemoryExtractCursorMapper cursorMapper,
            AppMemoryMapper memoryMapper,
            AppMemoryCandidateMapper candidateMapper,
            AppMemoryEvidenceMapper evidenceMapper,
            AppMemoryEventMapper eventMapper,
            AppMapper appMapper,
            GoodAppCacheService goodAppCacheService) {
        this.chatHistoryMapper = chatHistoryMapper;
        this.summaryMapper = summaryMapper;
        this.cursorMapper = cursorMapper;
        this.memoryMapper = memoryMapper;
        this.candidateMapper = candidateMapper;
        this.evidenceMapper = evidenceMapper;
        this.eventMapper = eventMapper;
        this.appMapper = appMapper;
        this.goodAppCacheService = goodAppCacheService;
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
        candidateMapper.unlinkAppId(appId);
        evidenceMapper.unlinkAppId(appId);
        eventMapper.unlinkAppId(appId);
        appMapper.deleteById(appId);
        goodAppCacheService.evictAfterCommit(appId, "删除应用");
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
