package com.lyw.appgeneration.service.rag.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 策展模板文档(JSON 反序列化目标)
 *
 * @author lyw
 */
@Data
@NoArgsConstructor
public class TemplateDoc {

    /** 知识文档结构版本 */
    private Integer schemaVersion;

    /** 唯一 ID,用于向量库 upsert */
    private String id;

    /** 父文档类型 */
    private RagDocumentKind documentKind;

    /** 类型:html / multi-file / vue-project,由目录推导 */
    private String type;

    /** 模板自身版本 */
    private String version;

    /** 前端框架 */
    private String framework;

    /** 编程语言 */
    private String language;

    /** 构建工具 */
    private String buildTool;

    /** 运行时依赖声明 */
    private Map<String, String> dependencies;

    /** 开发依赖声明 */
    private Map<String, String> devDependencies;

    /** 业务分类:auth / dashboard / landing / form / ecom 等 */
    private String category;

    /** 风格标签:minimal / corporate / playful / dark 等 */
    private List<String> style;

    /** 技术栈标签 */
    private List<String> tech;

    /** 模板标题(人类可读) */
    private String title;

    /** 人工编写的意图描述,embedding 就基于此字段 */
    private String embedText;

    /** 模板适用场景的详细说明 */
    private String description;

    /** 完整代码内容,可以是单段字符串或多文件列表 */
    private List<TemplateFile> files;

    /** 质量评分 0-1 */
    private Double qualityScore;

    @Data
    @NoArgsConstructor
    public static class TemplateFile {
        private String path;
        private String content;
    }
}
