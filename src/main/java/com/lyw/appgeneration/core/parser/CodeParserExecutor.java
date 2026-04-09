package com.lyw.appgeneration.core.parser;

import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;

/**
 * 代码解析执行器
 *
 * @author lyw
 */
public class CodeParserExecutor {

    private static final HtmlCodeParser HTML_CODE_PARSER = new HtmlCodeParser();

    private static final MultiFileCodeParser MULTI_FILE_CODE_PARSER = new MultiFileCodeParser();

    /**
     * 根据代码类型执行解析
     * @param codeContent
     * @param parserType
     * @return
     */
    public static Object executeParse(String codeContent, CodeGenTypeEnum parserType) {
        return switch (parserType) {
            case HTML -> HTML_CODE_PARSER.parseCode(codeContent);
            case MULTI_FILE -> MULTI_FILE_CODE_PARSER.parseCode(codeContent);
            default -> throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型");
        };
    }

}
