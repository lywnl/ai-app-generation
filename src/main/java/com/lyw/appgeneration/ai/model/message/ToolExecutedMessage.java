package com.lyw.appgeneration.ai.model.message;

import com.lyw.appgeneration.ai.tools.ToolStreamMessageRedactor;
import dev.langchain4j.service.tool.ToolExecution;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/**
 * 工具执行结果消息
 */
@Data
@EqualsAndHashCode(callSuper = true)
@NoArgsConstructor
public class ToolExecutedMessage extends StreamMessage {

    private String id;

    private String name;

    private String arguments;

    private String result;

    public ToolExecutedMessage(ToolExecution toolExecution) {
        super(StreamMessageTypeEnum.TOOL_EXECUTED.getValue());
        this.id = toolExecution.request().id();
        this.name = toolExecution.request().name();
        this.arguments = toolExecution.request().arguments();
        this.result = toolExecution.result();
    }

    public ToolExecutedMessage toClientSafeCopy() {
        ToolExecutedMessage copy = new ToolExecutedMessage();
        copy.setType(getType());
        copy.id = id;
        copy.name = name;
        copy.arguments = ToolStreamMessageRedactor.safeArguments(name, arguments);
        copy.result = ToolStreamMessageRedactor.safeResult(
                name, copy.arguments, result);
        return copy;
    }
}
