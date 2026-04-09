package com.lyw.appgeneration.ai.model;

import dev.langchain4j.model.output.structured.Description;
import lombok.Data;

/**
 * 多文件代码生成结果
 *
 * @author lyw
 */
@Description("生成多个代码文件的结果")
@Data
public class MultiFileCodeResult {

    @Description("HTML代码")
    private String htmlCode;

    @Description("CSS代码")
    private String cssCode;

    @Description("JS代码")
    private String jsCode;

    @Description("生成代码的描述")
    private String description;
}


