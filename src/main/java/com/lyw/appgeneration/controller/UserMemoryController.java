package com.lyw.appgeneration.controller;

import com.lyw.appgeneration.common.BaseResponse;
import com.lyw.appgeneration.common.ResultUtils;
import com.lyw.appgeneration.model.dto.memory.*;
import com.lyw.appgeneration.model.vo.*;
import com.lyw.appgeneration.service.UserMemoryManagementService;
import com.lyw.appgeneration.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 当前用户的记忆管理；用户身份始终来自登录会话，不接收客户端 userId。 */
@RestController
@RequestMapping("/user/memory")
public class UserMemoryController {
    private final UserService users;
    private final UserMemoryManagementService memories;

    public UserMemoryController(UserService users, UserMemoryManagementService memories) {
        this.users = users;
        this.memories = memories;
    }

    @PostMapping("/list")
    public BaseResponse<UserMemoryPageVO> list(@RequestBody UserMemoryQueryRequest body, HttpServletRequest request) {
        return ResultUtils.success(memories.list(users.getLoginUser(request).getId(), body));
    }

    @PostMapping("/preview")
    public BaseResponse<UserMemoryPreviewVO> preview(@RequestBody UserMemoryPreviewRequest body, HttpServletRequest request) {
        return ResultUtils.success(memories.preview(users.getLoginUser(request).getId(), body));
    }

    @PostMapping("/change")
    public BaseResponse<UserMemoryMutationVO> change(@RequestBody UserMemoryMutationRequest body, HttpServletRequest request) {
        return ResultUtils.success(memories.change(users.getLoginUser(request).getId(), body));
    }

    @PostMapping("/history")
    public BaseResponse<UserMemoryHistoryVO> history(@RequestBody UserMemoryQueryRequest body, HttpServletRequest request) {
        return ResultUtils.success(memories.history(users.getLoginUser(request).getId(), body));
    }
}
