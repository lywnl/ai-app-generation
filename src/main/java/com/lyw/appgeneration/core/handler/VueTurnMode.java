package com.lyw.appgeneration.core.handler;

/** Vue 在线回合的执行契约。 */
public enum VueTurnMode {
    /** 查询、解释和分析当前工程，不要求产生文件变更。 */
    READ_ONLY,
    /** 创建、修改、删除或修复工程，必须完成真实构建。 */
    MUTATION_REQUIRED
}
