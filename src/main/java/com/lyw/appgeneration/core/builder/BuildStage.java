package com.lyw.appgeneration.core.builder;

/**
 * Vue 工程构建结果所处阶段。
 */
public enum BuildStage {
    VALIDATION,
    NPM_INSTALL,
    NPM_BUILD,
    DIST_CHECK,
    SUCCESS
}
