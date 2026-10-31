package com.lyw.appgeneration.model.dto.memory;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Data;

@Data
public class UserMemoryMutationRequest {
    /** 每次明确操作生成一次，网络重试保留同一标识。 */
    private String requestId;
    /** CREATE / EDIT / ADOPT / REVOKE / CANCEL。 */
    private String action;
    private String category;
    private String attributeName;
    private String content;
    /** ACTIVE / CANDIDATE；只用于新增。 */
    private String saveMode;
    private VersionReference target;
    /** 页面展示的同属性正式版本；null 明确表示当时没有正式记录。 */
    private VersionReference expectedActive;

    public record VersionReference(
            @JsonDeserialize(using = MemoryIdStringDeserializer.class) String id,
            @JsonDeserialize(using = MemoryIdStringDeserializer.class) String revision) { }
}
