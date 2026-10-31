package com.lyw.appgeneration.model.vo;

import java.time.LocalDateTime;
import java.util.List;

public record UserMemoryHistoryVO(List<Entry> records, long totalRow, int pageNum, int pageSize) {
    public record Entry(String id, String sourceType, LocalDateTime createTime, List<Change> changes) { }
    public record Change(String action, String category, String attributeName, String beforeContent,
                         String afterContent, String targetId, String targetKind, String recordStatus) { }
}
