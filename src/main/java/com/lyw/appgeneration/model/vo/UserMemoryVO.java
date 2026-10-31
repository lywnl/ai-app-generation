package com.lyw.appgeneration.model.vo;

import java.time.LocalDateTime;

/** 页面仅接收当前用户的可管理数据，所有标识和版本均按字符串传输。 */
public record UserMemoryVO(String id, String revision, String category, String attributeName,
                           String content, String status, boolean requiresConfirmation,
                           LocalDateTime createTime, LocalDateTime updateTime,
                           ActiveMemory activeMemory) {
    public record ActiveMemory(String id, String revision, String content) { }
}
