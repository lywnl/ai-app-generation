package com.lyw.appgeneration.controller;

import com.lyw.appgeneration.common.BaseResponse;
import com.lyw.appgeneration.common.ResultUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查控制器
 *
 * @author lyw
 */
@Slf4j
@RestController("/health")
public class HealthController {

    @GetMapping("/")
    public BaseResponse<String> getHealth() {
        return ResultUtils.success("OK");
    }

}
