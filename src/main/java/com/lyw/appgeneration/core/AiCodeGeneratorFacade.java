package com.lyw.appgeneration.core;

import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.ai.AiCodeGeneratorService;
import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.ai.image.ImageCollectionService;
import com.lyw.appgeneration.ai.model.HtmlCodeResult;
import com.lyw.appgeneration.ai.model.MultiFileCodeResult;
import com.lyw.appgeneration.ai.model.message.AiResponseMessage;
import com.lyw.appgeneration.ai.model.message.ToolArgumentDeltaMessage;
import com.lyw.appgeneration.ai.model.message.ToolArgumentMessage;
import com.lyw.appgeneration.ai.model.message.ToolExecutedMessage;
import com.lyw.appgeneration.ai.model.message.ToolRequestMessage;
import com.lyw.appgeneration.ai.parser.ToolRequestStreamParser;
import com.lyw.appgeneration.core.parser.CodeParserExecutor;
import com.lyw.appgeneration.core.saver.CodeFileSaverExecutor;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * AI 代码生成门面
 *
 * @author lyw
 */
@Slf4j
@Service
public class AiCodeGeneratorFacade {

    @Resource
    private AiGeneratorServiceFactory aiGeneratorServiceFactory;

    @Resource
    private ImageCollectionService imageCollectionService;

    /**
     * 生成并保存代码
     * @param userMessage
     * @param bizType
     * @return
     */
    public File generateAndSaveCode(String userMessage, CodeGenTypeEnum bizType, long appId) {
        if (bizType == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成的类型不能为空");
        }
        // 根据ID 获取AI代码生成服务
        AiCodeGeneratorService aiCodeGeneratorService = aiGeneratorServiceFactory.getAiCodeGeneratorService(appId, bizType);
        return switch (bizType) {
           case HTML -> {
               HtmlCodeResult htmlCodeResult = aiCodeGeneratorService.generateHtmlCode(userMessage);
               yield  CodeFileSaverExecutor.executeSaver(htmlCodeResult, CodeGenTypeEnum.HTML, appId);
           }
           case MULTI_FILE -> {
               MultiFileCodeResult multiFileCodeResult = aiCodeGeneratorService.generateMultiFileCode(userMessage);
               yield  CodeFileSaverExecutor.executeSaver(multiFileCodeResult, CodeGenTypeEnum.MULTI_FILE, appId);
           }
            default -> {
                String errorMsg = "不支持的生成类型: " + bizType.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMsg);
            }
        };
    }

    /**
     * 统一入口：根据类型生成并保存代码（流式）
     *
     * @param userMessage     用户提示词
     * @param codeGenTypeEnum 生成类型
     */
    public Flux<String> generateAndSaveCodeStream(String userMessage, CodeGenTypeEnum codeGenTypeEnum, long appId) {
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "生成类型为空");
        }
        // 根据ID 获取AI代码生成服务
        AiCodeGeneratorService aiCodeGeneratorService = aiGeneratorServiceFactory.getAiCodeGeneratorService(appId, codeGenTypeEnum);
        return switch (codeGenTypeEnum) {
            case HTML -> {
                Flux<String> codeStream = aiCodeGeneratorService.generateHtmlCodeStream(userMessage);
                yield progressCodeStream(codeStream, CodeGenTypeEnum.HTML, appId);
            }
            case MULTI_FILE -> {
                String enhancedPrompt = imageCollectionService.enhancePrompt(userMessage);
                Flux<String> codeStream = aiCodeGeneratorService.generateMultiFileCodeStream(enhancedPrompt);
                yield progressCodeStream(codeStream, CodeGenTypeEnum.MULTI_FILE, appId);
            }
            case VUE_PROJECT -> {
                String enhancedPrompt = imageCollectionService.enhancePrompt(userMessage);
                TokenStream tokenStream = aiCodeGeneratorService.generateVueProjectCodeStream(appId, enhancedPrompt);
                yield processTokenStream(tokenStream);
            }
            default -> {
                String errorMsg = "不支持的生成类型: " + codeGenTypeEnum.getValue();
                throw new BusinessException(ErrorCode.SYSTEM_ERROR, errorMsg);
            }
        };
    }

    /**
     * 将 TokenStream 转换为 Flux<String>，并传递工具调用信息
     *
     * @param tokenStream TokenStream 对象
     * @return Flux<String> 流式响应
     */
    private Flux<String> processTokenStream(TokenStream tokenStream) {
        return Flux.create(sink -> {
            // 每个 tool call id 维护独立的字符级状态机。同一 id 的多次 partial 喂入同一 parser。
            Map<String, ToolRequestStreamParser> parsers = new HashMap<>();
            tokenStream.onPartialResponse((String partialResponse) -> {
                        AiResponseMessage aiResponseMessage = new AiResponseMessage(partialResponse);
                        sink.next(JSONUtil.toJsonStr(aiResponseMessage));
                    })
                    .onPartialToolExecutionRequest((index, toolExecutionRequest) -> {
                        String toolId = toolExecutionRequest.id();
                        String toolName = toolExecutionRequest.name();
                        // 首次出现该 id:发一条 TOOL_REQUEST(兼容现有下游去重逻辑),并创建 parser
                        ToolRequestStreamParser parser = parsers.get(toolId);
                        if (parser == null) {
                            sink.next(JSONUtil.toJsonStr(new ToolRequestMessage(toolExecutionRequest)));
                            parser = new ToolRequestStreamParser(toolName, evt -> {
                                switch (evt.type) {
                                    case DELTA -> sink.next(JSONUtil.toJsonStr(
                                            new ToolArgumentDeltaMessage(toolId, toolName, evt.key, evt.payload)));
                                    case VALUE_READY -> sink.next(JSONUtil.toJsonStr(
                                            new ToolArgumentMessage(toolId, toolName, evt.key, evt.payload)));
                                    case KEY_READY -> { /* 不单独下发,KEY 信息会在紧随的 DELTA/VALUE 中携带 */ }
                                }
                            });
                            parsers.put(toolId, parser);
                        }
                        parser.feed(toolExecutionRequest.arguments());
                    })
                    .onToolExecuted((ToolExecution toolExecution) -> {
                        // 某工具调用真正执行前,先 finish 对应 parser(确保字面量 value 也能 flush)
                        ToolRequestStreamParser parser = parsers.remove(toolExecution.request().id());
                        if (parser != null) parser.finish();
                        ToolExecutedMessage toolExecutedMessage = new ToolExecutedMessage(toolExecution);
                        sink.next(JSONUtil.toJsonStr(toolExecutedMessage));
                    })
                    .onCompleteResponse((ChatResponse response) -> {
                        parsers.values().forEach(ToolRequestStreamParser::finish);
                        sink.complete();
                    })
                    .onError((Throwable error) -> {
                        error.printStackTrace();
                        sink.error(error);
                    })
                    .start();
        });
    }


    /**
     * 通用流式代码处理方法
     * @param codeStream
     * @param codeGenTypeEnum
     * @return
     */
    private Flux<String> progressCodeStream(Flux<String> codeStream, CodeGenTypeEnum codeGenTypeEnum, long appId) {
        // 当流式返回生成代码完成后，再保存代码
        StringBuilder codeBuilder = new StringBuilder();
        return codeStream.doOnNext(chunk -> {
            // 实时收集代码片段
            codeBuilder.append(chunk);
        }).doOnComplete(() -> {
            // 流式返回完成后保存代码
            try {
                String completeHtmlCode = codeBuilder.toString();
                //执行器执行解析代码
                Object parseResult = CodeParserExecutor.executeParse(completeHtmlCode, codeGenTypeEnum);
                //执行器保存代码
                File savedDir = CodeFileSaverExecutor.executeSaver(parseResult, codeGenTypeEnum, appId);
                log.info("保存成功，路径为：" + savedDir.getAbsolutePath());
            } catch (Exception e) {
                log.error("保存失败: {}", e.getMessage());
            }
        });

    }

}
