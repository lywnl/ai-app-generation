package com.lyw.appgeneration.service.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.ai.AiCodeGenTypeRoutingService;
import com.lyw.appgeneration.ai.AiCodeGenTypeRoutingServiceFactory;
import com.lyw.appgeneration.ai.guardrail.annotation.PromptSafetyCheck;
import com.lyw.appgeneration.constants.AppConstant;
import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.core.builder.VueProjectBuilder;
import com.lyw.appgeneration.core.handler.StreamHandlerExecutor;
import com.lyw.appgeneration.core.handler.VueTurnContext;
import com.lyw.appgeneration.core.handler.GenerationStreamEvent;
import com.lyw.appgeneration.core.builder.VueBuildSessionManager;
import com.lyw.appgeneration.core.concurrency.AppOperationLeaseManager;
import com.lyw.appgeneration.ai.AiGeneratorServiceFactory;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.exception.ThrowUtils;
import com.lyw.appgeneration.mapper.AppMapper;
import com.lyw.appgeneration.model.dto.app.AppAddRequest;
import com.lyw.appgeneration.model.dto.app.AppQueryRequest;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.ChatHistoryMessageTypeEnum;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.model.vo.app.AppVO;
import com.lyw.appgeneration.model.vo.user.UserVO;
import com.lyw.appgeneration.monitor.MonitorContext;
import com.lyw.appgeneration.monitor.MonitorContextHolder;
import com.lyw.appgeneration.ratelimiter.annotation.RateLimit;
import com.lyw.appgeneration.ratelimiter.enums.RateLimitType;
import com.lyw.appgeneration.service.AppService;
import com.lyw.appgeneration.service.AppStoragePathResolver;
import com.lyw.appgeneration.service.ChatHistoryService;
import com.lyw.appgeneration.service.ProjectDownloadService;
import com.lyw.appgeneration.service.ScreenshotService;
import com.lyw.appgeneration.service.UserService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.io.File;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * 应用 服务层实现。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
@Slf4j
@Service
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    @Resource
    private UserService userService;

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

    @Resource
    private AiGeneratorServiceFactory aiGeneratorServiceFactory;

    @Resource
    private AppOperationLeaseManager appOperationLeaseManager;

    @Resource
    private VueBuildSessionManager vueBuildSessionManager;

    @Resource
    private ChatHistoryService chatHistoryService;

    @Resource
    private StreamHandlerExecutor streamHandlerExecutor;

    @Resource
    private VueProjectBuilder vueProjectBuilder;

    @Resource
    private ScreenshotService screenshotService;

    @Resource
    private AppStoragePathResolver appStoragePathResolver;

    @Resource
    private ProjectDownloadService projectDownloadService;

    @Resource
    private AiCodeGenTypeRoutingServiceFactory aiCodeGenTypeRoutingServiceFactory;

    @Override
    public AppVO getAppVO(App app) {
        if (app == null) {
            return null;
        }
        AppVO appVO = new AppVO();
        BeanUtil.copyProperties(app, appVO);
        // 关联查询用户信息
        Long userId = app.getUserId();
        if (userId != null) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            appVO.setUser(userVO);
        }

        return appVO;
    }

    @Override
    public QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest) {
        if (appQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = appQueryRequest.getId();
        String appName = appQueryRequest.getAppName();
        String cover = appQueryRequest.getCover();
        String initPrompt = appQueryRequest.getInitPrompt();
        String codeGenType = appQueryRequest.getCodeGenType();
        String deployKey = appQueryRequest.getDeployKey();
        Integer priority = appQueryRequest.getPriority();
        Long userId = appQueryRequest.getUserId();
        String sortField = appQueryRequest.getSortField();
        String sortOrder = appQueryRequest.getSortOrder();
        return QueryWrapper.create()
                .eq("id", id)
                .like("appName", appName)
                .like("cover", cover)
                .like("initPrompt", initPrompt)
                .eq("codeGenType", codeGenType)
                .eq("deployKey", deployKey)
                .eq("priority", priority)
                .eq("userId", userId)
                .orderBy(sortField, "ascend".equals(sortOrder));
    }

    @Override
    public List<AppVO> getAppVOList(List<App> appList) {
        if (CollUtil.isEmpty(appList)) {
            return new ArrayList<>();
        }
        // 批量获取用户信息，避免 N+1 查询问题
        Set<Long> userIds = appList.stream()
                .map(App::getUserId)
                .collect(Collectors.toSet());
        Map<Long, UserVO> userVOMap = userService.listByIds(userIds).stream()
                .collect(Collectors.toMap(User::getId, userService::getUserVO));
        return appList.stream().map(app -> {
            AppVO appVO = getAppVO(app);
            UserVO userVO = userVOMap.get(app.getUserId());
            appVO.setUser(userVO);
            return appVO;
        }).collect(Collectors.toList());
    }

    @Override
    @RateLimit(limitType = RateLimitType.USER, rate = 5, rateInterval = 60, message = "AI请求过于频繁，请稍后再试")
    @PromptSafetyCheck
    public Flux<GenerationStreamEvent> chatToGenCode(
            Long appId, String message, User loginUser) {
        // 1. 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(StrUtil.isBlank(message), ErrorCode.PARAMS_ERROR, "用户消息不能为空");
        // 2. 查询应用信息
        App app = this.getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        // 3. 验证用户是否有权限访问该应用，仅本人可以生成代码
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该应用");
        }
        // 4. 获取应用的代码生成类型
        String codeGenTypeStr = app.getCodeGenType();
        CodeGenTypeEnum codeGenTypeEnum = CodeGenTypeEnum.getEnumByValue(codeGenTypeStr);
        if (codeGenTypeEnum == null) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "不支持的代码生成类型");
        }
        if (codeGenTypeEnum == CodeGenTypeEnum.VUE_PROJECT) {
            return generateVueTurn(appId, message, loginUser);
        }
        //5. 判断是否首次对话（必须在保存用户消息之前，否则 count 永远 >= 1）
        boolean isFirstMessage = !chatHistoryService.existsByAppId(appId);
        //6. 调用AI前, 先将用户消息保存到数据库
        chatHistoryService.addChatMessage(appId, message, ChatHistoryMessageTypeEnum.USER.getValue(), loginUser.getId());
        //7. 设置监控上下文
        MonitorContextHolder.setContext(
                MonitorContext.builder()
                        .userId(loginUser.getId().toString())
                        .appId(appId.toString())
                        .build()

        );
        //8. 调用 AI 生成代码；仅首次对话时触发图片收集
        Flux<String> codeStream = aiCodeGeneratorFacade.generateAndSaveCodeStream(message, codeGenTypeEnum, appId, isFirstMessage);
        //9. 收集AI响应的内容并且在完成后保存到数据库;
        return streamHandlerExecutor.doExecute(codeStream, chatHistoryService, appId, loginUser, codeGenTypeEnum)
                .doFinally(signalType -> {
                    // 流结束时清理（无论成功/失败/取消）
                    MonitorContextHolder.clearContext();

                });
    }

    private Flux<GenerationStreamEvent> generateVueTurn(
            long appId, String message, User loginUser) {
        return Flux.defer(() -> {
            String turnId = UUID.randomUUID().toString();
            AppOperationLeaseManager.AppOperationLease operationLease =
                    appOperationLeaseManager.acquire(
                            appId,
                            AppOperationLeaseManager.AppOperationType.GENERATE,
                            turnId);
            VueBuildSessionManager.VueBuildLease vueLease = null;
            VueTurnContext context = null;
            try {
                vueLease = vueBuildSessionManager.open(
                        operationLease, loginUser.getId(), turnId);
                context = new VueTurnContext(
                        appId, loginUser.getId(), turnId,
                        operationLease, vueLease);
                VueTurnContext finalContext = context;
                Flux<String> codeStream = Flux.defer(() -> finalContext
                        .tryCallCallback(() -> prepareVueTurn(
                                appId, message, loginUser, finalContext))
                        .orElseGet(() -> Flux.error(new IllegalStateException(
                                "Vue 回合准备阶段已取消"))))
                        .subscribeOn(Schedulers.boundedElastic());
                MonitorContextHolder.setContext(MonitorContext.builder()
                        .userId(loginUser.getId().toString())
                        .appId(Long.toString(appId)).build());
                return streamHandlerExecutor.doExecuteVue(codeStream, context)
                        .doFinally(ignored -> MonitorContextHolder.clearContext());
            } catch (RuntimeException exception) {
                if (context != null) {
                    context.closeResources();
                } else {
                    if (vueLease != null) {
                        vueLease.close();
                    }
                    operationLease.close();
                }
                return Flux.error(exception);
            }
        });
    }

    private Flux<String> prepareVueTurn(
            long appId, String message, User loginUser,
            VueTurnContext context) {
        var lastMessage = chatHistoryService.getLastMessage(appId);
        context.ensureTerminalOpen();
        boolean hasHistory = lastMessage != null;
        if (hasHistory && ChatHistoryMessageTypeEnum.USER.getValue()
                .equals(lastMessage.getMessageType())) {
            boolean repaired = chatHistoryService.repairOrphanUserTurn(
                    appId, loginUser.getId(),
                    "生成过程中遇到系统异常，请稍后重试。");
            if (!repaired) {
                throw new BusinessException(
                        ErrorCode.OPERATION_ERROR,
                        "修复上一轮未完成对话失败");
            }
            aiGeneratorServiceFactory.prepareVueColdRebuild(appId);
            context.ensureTerminalOpen();
        }
        var generatorService = aiCodeGeneratorFacade.prepareVueGenerator(appId);
        context.ensureTerminalOpen();
        boolean saved = chatHistoryService.addChatMessage(
                appId, message, ChatHistoryMessageTypeEnum.USER.getValue(),
                loginUser.getId());
        if (!saved) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR, "保存用户消息失败");
        }
        context.markUserCommitted();
        context.ensureTerminalOpen();
        return aiCodeGeneratorFacade.generateVueProjectStream(
                message, appId, !hasHistory, context, generatorService);
    }

    @Override
    public String deployApp(Long appId, User loginUser) {
        //1. 参数校验
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        //2. 查询用户信息
        App app = getById(appId);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        //3. 验证用户是否有权限访问该应用，仅本人可以部署应用
        if (!app.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限部署该应用");
        }
        //4. 检查是否已经存在deploy key
        String deployKey = app.getDeployKey();
        //如果不存在deploy key，则生成deploy key
        if (StrUtil.isBlank(deployKey)) {
            deployKey = RandomUtil.randomString(6);
        }
        //5. 获取代码生成类型, 获取原始的代码生成路径
        String codeGenType = app.getCodeGenType();
        String sourceDirName = codeGenType + "_" + appId;
        String sourceDirPath = AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + sourceDirName;
        //6. 检验文件是否存在
        File file = new File(sourceDirPath);
        if (!file.exists() || !file.isDirectory()) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "代码生成目录不存在");
        }
        //7. Vue项目特殊处理：执行构建
        CodeGenTypeEnum enumByValue = CodeGenTypeEnum.getEnumByValue(codeGenType);
        if (enumByValue == CodeGenTypeEnum.VUE_PROJECT) {
            //vue项目构建
            boolean result = vueProjectBuilder.buildProject(sourceDirPath);
            ThrowUtils.throwIf(!result, ErrorCode.SYSTEM_ERROR, "Vue项目构建失败 请重试");
            //检查dist目录是否存在
            File distDir = new File(sourceDirPath, "dist");
            ThrowUtils.throwIf(!distDir.exists() || !distDir.isDirectory(), ErrorCode.SYSTEM_ERROR, "Vue项目构建失败 请重试");
            //构建成功 复制到部署目录
            file = distDir;
        }
        //8. 拷贝文件到部署目录
        String deployPath = AppConstant.CODE_DEPLOY_ROOT_DIR + File.separator + deployKey;
        try {
            FileUtil.copyContent(file, new File(deployPath), true);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "部署目录拷贝失败: " + e.getMessage());
        }
        //9. 更新数据库
        App updateApp = new App();
        updateApp.setDeployKey(deployKey);
        updateApp.setId(appId);
        updateApp.setDeployedTime(LocalDateTime.now());
        boolean result = updateById(updateApp);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "更新部署失败");
        //10. 返回可访问的地址
        String formatUrl = String.format("%s/%s/", AppConstant.CODE_DEPLOY_HOST, deployKey);
        //11. 异步生成截图并且更新应用封面
        generateAppScreenshotAsync(appId, formatUrl);

        return formatUrl;
    }

    @Override
    public void downloadApp(
            Long appId, User loginUser, HttpServletResponse response) {
        ThrowUtils.throwIf(appId == null || appId <= 0,
                ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null
                        || loginUser.getId() <= 0,
                ErrorCode.NOT_LOGIN_ERROR, "用户未登录");
        ThrowUtils.throwIf(response == null,
                ErrorCode.PARAMS_ERROR, "响应对象不能为空");
        App app = getById(appId);
        ThrowUtils.throwIf(app == null,
                ErrorCode.NOT_FOUND_ERROR, "应用不存在");
        if (!loginUser.getId().equals(app.getUserId())) {
            throw new BusinessException(
                    ErrorCode.NO_AUTH_ERROR, "无权限下载该应用代码");
        }
        CodeGenTypeEnum codeGenType = CodeGenTypeEnum.getEnumByValue(
                app.getCodeGenType());
        ThrowUtils.throwIf(codeGenType == null,
                ErrorCode.PARAMS_ERROR, "代码生成类型无效");

        String ownerToken = "download-" + UUID.randomUUID();
        try (AppOperationLeaseManager.AppOperationLease ignored =
                     acquireDownloadLease(appId, ownerToken)) {
            var sourceDirectory = appStoragePathResolver
                    .resolveSourceDirectory(app);
            projectDownloadService.downloadProjectAsZip(
                    sourceDirectory, String.valueOf(appId), response);
        }
    }

    private AppOperationLeaseManager.AppOperationLease acquireDownloadLease(
            long appId, String ownerToken) {
        try {
            return appOperationLeaseManager.acquire(
                    appId,
                    AppOperationLeaseManager.AppOperationType.DOWNLOAD,
                    ownerToken);
        } catch (AppOperationLeaseManager.ActiveAppOperationException exception) {
            throw new BusinessException(
                    ErrorCode.OPERATION_ERROR,
                    "应用正在生成中，暂时无法下载");
        }
    }

    /**
     * 重写删除方法，删除应用时，需要删除应用相关的所有数据，包括代码生成目录、部署目录、数据库记录等
     * @param id
     * @return
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id) {
        if (id == null) {
            return false;
        }
        Long appId = Long.parseLong(id.toString());
        if (appId <= 0) {
            return false;
        }
        App app = getById(appId);
        if (app == null) {
            return false;
        }

        // DB 操作 — 事务保护，失败自动回滚
        chatHistoryService.deleteByAppId(appId);
        super.removeById(appId);

        // 文件操作 — 尽力清理，失败不影响事务
        try {
            FileUtil.del(AppConstant.CODE_OUTPUT_ROOT_DIR + File.separator + app.getCodeGenType() + "_" + appId);
            FileUtil.del(AppConstant.CODE_DEPLOY_ROOT_DIR + File.separator + app.getDeployKey());
        } catch (Exception e) {
            log.warn("应用记录已删除，但文件清理失败: appId={}, error={}", appId, e.getMessage());
        }

        return true;
    }

    public void generateAppScreenshotAsync(Long appId, String appUrl) {
        //调用截图服务生成截图并且上传
        String screenshotUrl = StrUtil.trim(screenshotService.generateAndUploadScreenshot(appUrl));
        screenshotUrl = StrUtil.removeSuffix(screenshotUrl, "/");
        ThrowUtils.throwIf(StrUtil.isBlank(screenshotUrl), ErrorCode.OPERATION_ERROR, "截图地址为空，更新应用封面失败");
        //更新应用封面字段
        App updateApp = new App();
        updateApp.setId(appId);
        updateApp.setCover(screenshotUrl);
        boolean result = updateById(updateApp);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "更新应用封面失败");
    }

    @Override
    public Long addApp(AppAddRequest appAddRequest, User loginUser) {
        // 参数校验
        String initPrompt = appAddRequest.getInitPrompt();
        ThrowUtils.throwIf(StrUtil.isBlank(initPrompt), ErrorCode.PARAMS_ERROR, "初始化 prompt 不能为空");
        // 构造入库对象
        App app = new App();
        BeanUtil.copyProperties(appAddRequest, app);
        app.setUserId(loginUser.getId());
        // 应用名称暂时为 initPrompt 前 12 位
        app.setAppName(initPrompt.substring(0, Math.min(initPrompt.length(), 12)));
        // 根据 AI 选择代码生成类型
        AiCodeGenTypeRoutingService routingService = aiCodeGenTypeRoutingServiceFactory.createAiCodeGenTypeRoutingService();
        CodeGenTypeEnum selectGenType = routingService.routeCodeGenType(initPrompt);
        app.setCodeGenType(selectGenType.getValue());
        // 插入数据库
        boolean result = save(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);

        return app.getId();
    }
}
