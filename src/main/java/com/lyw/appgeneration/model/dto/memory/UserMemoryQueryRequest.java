package com.lyw.appgeneration.model.dto.memory;

import lombok.Data;

@Data
public class UserMemoryQueryRequest {
    private String status = "ACTIVE";
    private String category;
    private String keyword;
    private int pageNum = 1;
    private int pageSize = 10;
}
