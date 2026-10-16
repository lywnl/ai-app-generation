package com.lyw.appgeneration.ai.image.tools;

import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import com.alibaba.dashscope.exception.NoApiKeyException;

/** 将外部图片接口与本地轮询策略分开，SDK 仍负责通信和认证。 */
interface LogoImageClient {
    ImageSynthesisResult asyncCall(ImageSynthesisParam param) throws NoApiKeyException;

    ImageSynthesisResult fetch(String taskId, String apiKey) throws NoApiKeyException;

    static LogoImageClient dashScope() {
        ImageSynthesis sdk = new ImageSynthesis();
        return new LogoImageClient() {
            @Override
            public ImageSynthesisResult asyncCall(ImageSynthesisParam param) throws NoApiKeyException {
                return sdk.asyncCall(param);
            }

            @Override
            public ImageSynthesisResult fetch(String taskId, String apiKey) throws NoApiKeyException {
                return sdk.fetch(taskId, apiKey);
            }
        };
    }
}
