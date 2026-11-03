package com.lyw.appgeneration.core.handler;

import java.util.Objects;

/** 已校验的本轮模式及资料需求；不能用资源需求反向推断修改权限。 */
public record VueTurnDecision(VueTurnMode mode, boolean needRag, boolean needImageSearch) {
    public VueTurnDecision {
        Objects.requireNonNull(mode, "Vue 回合模式不能为空");
        if (mode == VueTurnMode.READ_ONLY) {
            needRag = false;
            needImageSearch = false;
        }
    }
}
