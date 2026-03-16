package com.lyw.appgeneration.common;

import lombok.Data;

/**
 * 分页请求参数
 *
 * @author system
 */
@Data
public class PageRequest {

    /**
     * 当前页号：有多少页
     */
    private int pageNum = 1;

    /**
     * 页面大小：一页多少条
     */
    private int pageSize = 10;

    /**
     * 排序字段
     */
    private String sortField;

    /**
     * 排序顺序（默认降序）
     */
    private String sortOrder = "descend";
}