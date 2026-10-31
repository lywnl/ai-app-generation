package com.lyw.appgeneration.model.vo;

import java.util.List;

public record UserMemoryPageVO(List<UserMemoryVO> records, long totalRow, int pageNum, int pageSize,
                               long activeCount, long pendingCount) { }
