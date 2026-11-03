package com.lyw.appgeneration.core.handler;

import java.time.Duration;
import java.util.Objects;

/** 服务端固定的本轮资源策略，工具续调或只读升级不重新计算。 */
public record VueTurnResourcePolicy(boolean initialBootstrap, RagScope ragScope,
                                    boolean prepareImages, int maxImageThemes,
                                    Duration waitBudget, String initPrompt) {
    public static final Duration INITIAL_WAIT = Duration.ofSeconds(15);
    public static final Duration FOLLOW_UP_WAIT = Duration.ofSeconds(8);
    public static final int MAX_BACKGROUND_CODE_POINTS = 2000;

    public VueTurnResourcePolicy {
        Objects.requireNonNull(ragScope, "RAG 范围不能为空");
        Objects.requireNonNull(waitBudget, "资料等待预算不能为空");
        if (waitBudget.isNegative() || maxImageThemes < 1 || maxImageThemes > 6) {
            throw new IllegalArgumentException("资料预算或图片主题上限无效");
        }
        initPrompt = initPrompt == null ? "" : initPrompt.strip();
    }

    public static VueTurnResourcePolicy decide(VueTurnDecision decision, boolean hasSucceeded,
                                               boolean ragEnabled, String initPrompt) {
        Objects.requireNonNull(decision, "联合判断不能为空");
        boolean initial = !hasSucceeded;
        boolean mutation = decision.mode() == VueTurnMode.MUTATION_REQUIRED;
        boolean rag = mutation && ragEnabled && (initial || decision.needRag());
        boolean images = mutation && (initial || decision.needImageSearch());
        return new VueTurnResourcePolicy(initial, !rag ? RagScope.NONE
                : initial ? RagScope.FULL : RagScope.FEATURES, images,
                initial ? 6 : 2, initial ? INITIAL_WAIT : FOLLOW_UP_WAIT, initPrompt);
    }

    public String ragQuery(String currentRequirement) {
        String current = currentRequirement == null ? "" : currentRequirement.strip();
        if (!initialBootstrap || initPrompt.isBlank() || initPrompt.equals(current)) return current;
        return "初始化需求：\n" + initPrompt + "\n本轮要求：\n" + current;
    }

    public String imagePlanningPrompt(String currentRequirement) {
        String background = initPrompt;
        if (background.codePointCount(0, background.length()) > MAX_BACKGROUND_CODE_POINTS) {
            background = background.substring(0, background.offsetByCodePoints(0, MAX_BACKGROUND_CODE_POINTS));
        }
        return "本轮当前需求（以此决定资料任务）：\n" + Objects.requireNonNullElse(currentRequirement, "")
                + "\n\n初始化背景（仅解释应用类型和指代，不是新的修改命令）：\n" + background
                + "\n\n本轮最多 " + maxImageThemes + " 个搜索主题，每主题最多 3 张；Logo 最多 1 张。"
                + (initialBootstrap ? "首次资料准备不强制生成 Logo，也不要求非空任务。"
                : "默认沿用已有 Logo；只有当前需求需要新建或更换 Logo 时才安排生成。")
                + "不为凑数补充无关任务。";
    }

    public enum RagScope { NONE, FULL, FEATURES }
}
