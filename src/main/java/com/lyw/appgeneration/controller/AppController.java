package com.lyw.appgeneration.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.json.JSONUtil;
import com.lyw.appgeneration.ai.AiCodeGenTypeRoutingService;
import com.lyw.appgeneration.annotation.AuthCheck;
import com.lyw.appgeneration.common.BaseResponse;
import com.lyw.appgeneration.common.DeleteRequest;
import com.lyw.appgeneration.common.ResultUtils;
import com.lyw.appgeneration.constants.AppConstant;
import com.lyw.appgeneration.constants.UserConstant;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.exception.GenerationPreflightException;
import com.lyw.appgeneration.exception.ThrowUtils;
import com.lyw.appgeneration.core.handler.GenerationStreamEvent;
import com.lyw.appgeneration.model.dto.app.*;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.monitor.AppLifecycleMetricsCollector;
import com.lyw.appgeneration.model.vo.app.AppVO;
import com.lyw.appgeneration.service.AppService;
import com.lyw.appgeneration.service.UserService;
import dev.langchain4j.service.ModelRequestGate;
import dev.langchain4j.service.ModelRequestGateException;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 应用 控制层。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
@RestController
@RequestMapping("/app")
public class AppController {

    private static final Duration SSE_HEARTBEAT_INTERVAL = Duration.ofSeconds(15);
    private static final String VUE_TURN_PROTOCOL = "vue-turn/v1";

    @Autowired
    private AppService appService;

    @Resource
    private UserService userService;

    @Resource
    private AppLifecycleMetricsCollector appLifecycleMetricsCollector;

    /**
     * 下载应用代码
     *
     * @param appId    应用ID
     * @param request  请求
     * @param response 响应
     */
    @GetMapping("/download/{appId}")
    public void downloadAppCode(@PathVariable Long appId,
                                HttpServletRequest request,
                                HttpServletResponse response) {
        User loginUser = userService.getLoginUser(request);
        appService.downloadApp(appId, loginUser, response);
    }

    @PostMapping(value = "/chat/gen/code",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> chatToGenCode(
            @RequestBody AppChatGenerateRequest requestBody,
            HttpServletRequest request) {
        return Flux.defer(() -> {
            AppLifecycleMetricsCollector.SseProtocolObservation protocolObservation =
                    appLifecycleMetricsCollector.startSseProtocolObservation();
            AppLifecycleMetricsCollector.SsePublisherObservation publisherObservation =
                    appLifecycleMetricsCollector.startSsePublisherObservation();
            Mono<Flux<GenerationStreamEvent>> publisher = Mono.fromCallable(() -> {
                if (requestBody == null) {
                    throw new BusinessException(
                            ErrorCode.PARAMS_ERROR, "请求体不能为空");
                }
                long appId = requestBody.requireAppId();
                String message = requestBody.requireMessage();
                User loginUser = userService.getLoginUser(request);
                return appService.chatToGenCode(appId, message, loginUser);
            }).onErrorMap(BusinessException.class, this::toBusinessPreflight)
                    .onErrorMap(error -> !(error instanceof
                                    GenerationPreflightException),
                            GenerationPreflightException::system);
            Flux<GenerationStreamEvent> business = publisher.flatMapMany(
                            Function.identity())
                    .onErrorMap(this::isInitialGateRejection,
                            this::toInitialGatePreflight);
            Flux<ServerSentEvent<String>> protocol =
                    encodeBusinessWithHeartbeat(observeVueProtocolOutcome(
                            business, protocolObservation))
                            .onErrorResume(
                                    GenerationPreflightException.class,
                                    error -> {
                                        protocolObservation.complete(
                                                AppLifecycleMetricsCollector
                                                        .SseProtocolResult
                                                        .BUSINESS_ERROR,
                                                errorKind(error));
                                        return businessErrorEvent(error);
                                    });
            Flux<ServerSentEvent<String>> done = Mono
                    .fromSupplier(this::doneEvent)
                    .doOnNext(ignored -> protocolObservation.complete(
                            AppLifecycleMetricsCollector.SseProtocolResult.DONE,
                            AppLifecycleMetricsCollector.SseErrorKind.NONE))
                    .flux();
            return protocol.concatWith(done)
                    .doFinally(signal -> publisherObservation.complete(
                            publisherResult(signal)));
        });
    }

    private AppLifecycleMetricsCollector.SseErrorKind errorKind(
            GenerationPreflightException error) {
        return error.kind() == GenerationPreflightException.Kind.BUSINESS
                ? AppLifecycleMetricsCollector.SseErrorKind.BUSINESS
                : AppLifecycleMetricsCollector.SseErrorKind.SYSTEM;
    }

    private GenerationPreflightException toBusinessPreflight(
            BusinessException error) {
        return GenerationPreflightException.business(
                error.getCode(), error.getMessage(), error);
    }

    private boolean isInitialGateRejection(Throwable error) {
        return error instanceof ModelRequestGateException rejection
                && rejection.stage()
                == ModelRequestGateException.Stage.INITIAL;
    }

    private GenerationPreflightException toInitialGatePreflight(
            Throwable error) {
        ModelRequestGateException rejection =
                (ModelRequestGateException) error;
        if (rejection.status()
                == ModelRequestGate.Status.HARD_LIMIT_REJECTED) {
            return GenerationPreflightException.business(
                    ErrorCode.OPERATION_ERROR.getCode(),
                    rejection.getMessage(), rejection);
        }
        return GenerationPreflightException.system(rejection);
    }

    private Flux<GenerationStreamEvent> observeVueProtocolOutcome(
            Flux<GenerationStreamEvent> business,
            AppLifecycleMetricsCollector.SseProtocolObservation observation) {
        return business.doOnNext(event -> {
            if (event instanceof GenerationStreamEvent.TurnOutcome turnEvent) {
                AppLifecycleMetricsCollector.SseProtocolResult result = switch (
                        turnEvent.message().getOutcome()) {
                    case PROTOCOL_ERROR ->
                            AppLifecycleMetricsCollector.SseProtocolResult.PROTOCOL_ERROR;
                    case SYSTEM_ERROR ->
                            AppLifecycleMetricsCollector.SseProtocolResult.SYSTEM_ERROR;
                    default -> null;
                };
                if (result != null) {
                    observation.complete(result,
                            AppLifecycleMetricsCollector.SseErrorKind.NONE);
                }
            }
        });
    }

    private AppLifecycleMetricsCollector.SsePublisherResult publisherResult(
            SignalType signal) {
        return switch (signal) {
            case CANCEL -> AppLifecycleMetricsCollector.SsePublisherResult.SUBSCRIBER_CANCELLED;
            case ON_ERROR -> AppLifecycleMetricsCollector.SsePublisherResult.PUBLISHER_ERROR;
            default -> AppLifecycleMetricsCollector.SsePublisherResult.COMPLETED;
        };
    }

    private Flux<ServerSentEvent<String>> encodeBusinessWithHeartbeat(
            Flux<GenerationStreamEvent> business) {
        return business.publish(shared -> {
            Flux<ServerSentEvent<String>> body = shared.map(this::encodeBusinessEvent);
            Flux<ServerSentEvent<String>> heartbeat = shared
                    .map(ignored -> 0L)
                    .onErrorComplete()
                    .startWith(0L)
                    .switchMap(ignored -> Mono.delay(SSE_HEARTBEAT_INTERVAL)
                            .repeat()
                            .map(tick -> heartbeatEvent()))
                    .takeUntilOther(shared.ignoreElements().onErrorComplete());
            return Flux.merge(body, heartbeat);
        });
    }

    private ServerSentEvent<String> encodeBusinessEvent(
            GenerationStreamEvent event) {
        if (event instanceof GenerationStreamEvent.ContextCompression
                compressionEvent) {
            var compression = compressionEvent.message();
            Map<String, Object> data = Map.of(
                    "protocol", compression.protocol(),
                    "phase", compression.phase().name(),
                    "message", compression.message());
            return ServerSentEvent.<String>builder()
                    .event("context-compression")
                    .data(JSONUtil.toJsonStr(data))
                    .build();
        }
        if (event instanceof GenerationStreamEvent.ToolProtocolRecovery
                recoveryEvent) {
            var recovery = recoveryEvent.message();
            Map<String, Object> data = Map.of(
                    "protocol", recovery.protocol(),
                    "phase", recovery.phase().name(),
                    "message", recovery.message());
            return ServerSentEvent.<String>builder()
                    .event("tool-protocol-recovery")
                    .data(JSONUtil.toJsonStr(data))
                    .build();
        }
        if (event instanceof GenerationStreamEvent.TurnOutcome turnEvent) {
            var outcome = turnEvent.message();
            Map<String, Object> data = Map.of(
                    "protocol", VUE_TURN_PROTOCOL,
                    "outcome", outcome.getOutcome().name(),
                    "message", outcome.getMessage(),
                    "refreshPreview", outcome.isShouldRefreshPreview());
            return ServerSentEvent.<String>builder()
                    .event("turn-outcome")
                    .data(JSONUtil.toJsonStr(data))
                    .build();
        }
        String chunk = ((GenerationStreamEvent.Content) event).text();
        return ServerSentEvent.<String>builder()
                .data(JSONUtil.toJsonStr(Map.of("d", chunk)))
                .build();
    }

    private Flux<ServerSentEvent<String>> businessErrorEvent(
            GenerationPreflightException error) {
        Map<String, Object> data = Map.of(
                "protocol", "generation-error/v1",
                "kind", error.kind().name(),
                "code", error.code(),
                "message", error.safeMessage());
        return Flux.just(ServerSentEvent.<String>builder()
                .event("business-error")
                .data(JSONUtil.toJsonStr(data))
                .build());
    }

    private ServerSentEvent<String> heartbeatEvent() {
        return ServerSentEvent.<String>builder()
                .event("heartbeat")
                .data(JSONUtil.toJsonStr(Map.of(
                        "timestamp", System.currentTimeMillis())))
                .build();
    }

    private ServerSentEvent<String> doneEvent() {
        return ServerSentEvent.<String>builder()
                .event("done")
                .data("")
                .build();
    }

    /**
     * 应用部署
     *
     * @param appDeployRequest 部署请求
     * @param request          请求
     * @return 部署 URL
     */
    @PostMapping("/deploy")
    public BaseResponse<String> deployApp(@RequestBody AppDeployRequest appDeployRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appDeployRequest == null, ErrorCode.PARAMS_ERROR);
        Long appId = appDeployRequest.getAppId();
        ThrowUtils.throwIf(appId == null || appId <= 0, ErrorCode.PARAMS_ERROR, "应用 ID 不能为空");
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        // 调用服务部署应用
        String deployUrl = appService.deployApp(appId, loginUser);
        return ResultUtils.success(deployUrl);
    }

    /**
     * 创建应用
     *
     * @param appAddRequest 创建应用请求
     * @param request       请求
     * @return 应用 id
     */
    @PostMapping("/add")
    public BaseResponse<Long> addApp(@RequestBody AppAddRequest appAddRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(appAddRequest == null, ErrorCode.PARAMS_ERROR);
        // 获取当前登录用户
        User loginUser = userService.getLoginUser(request);
        Long appId = appService.addApp(appAddRequest, loginUser);
        return ResultUtils.success(appId);
    }

    /**
     * 更新应用（用户只能更新自己的应用名称）
     *
     * @param appUpdateRequest 更新请求
     * @param request          请求
     * @return 更新结果
     */
    @PostMapping("/update")
    public BaseResponse<Boolean> updateApp(@RequestBody AppUpdateRequest appUpdateRequest, HttpServletRequest request) {
        if (appUpdateRequest == null || appUpdateRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        long id = appUpdateRequest.getId();
        // 判断是否存在
        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人可更新
        if (!oldApp.getUserId().equals(loginUser.getId())) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
        App app = new App();
        app.setId(id);
        app.setAppName(appUpdateRequest.getAppName());
        // 设置编辑时间
        app.setEditTime(LocalDateTime.now());
        boolean result = appService.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 删除应用（用户只能删除自己的应用）
     *
     * @param deleteRequest 删除请求
     * @param request       请求
     * @return 删除结果
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteApp(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        long id = deleteRequest.getId();
        boolean result = appService.deleteApp(id, loginUser);
        return ResultUtils.success(result);
    }

    /**
     * 根据 id 获取应用详情
     *
     * @param id 应用 id
     * @return 应用详情
     */
    @GetMapping("/get/vo")
    public BaseResponse<AppVO> getAppVOById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        App app = appService.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        // 获取封装类（包含用户信息）
        return ResultUtils.success(appService.getAppVO(app));
    }

    /**
     * 分页获取当前用户创建的应用列表
     *
     * @param appQueryRequest 查询请求
     * @param request         请求
     * @return 应用列表
     */
    @PostMapping("/my/list/page/vo")
    public BaseResponse<Page<AppVO>> listMyAppVOByPage(@RequestBody AppQueryRequest appQueryRequest,
            HttpServletRequest request) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        // 限制每页最多 20 个
        long pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize > 20, ErrorCode.PARAMS_ERROR, "每页最多查询 20 个应用");
        long pageNum = appQueryRequest.getPageNum();
        // 只查询当前用户的应用
        appQueryRequest.setUserId(loginUser.getId());
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        // 数据封装
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }

    /**
     * 分页获取精选应用列表
     *
     * @param appQueryRequest 查询请求
     * @return 精选应用列表
     */
    @PostMapping("/good/list/page/vo")
    @Cacheable(
            value = "good_app_page",
            key = "@appDeployUrlBuilder.cacheNamespace() + ':' + "
                    + "T(com.lyw.appgeneration.utils.CacheKeyUtils)"
                    + ".generateKey(#appQueryRequest)",
            condition = "#appQueryRequest.pageNum <= 10"
    )
    public BaseResponse<Page<AppVO>> listGoodAppVOByPage(@RequestBody AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        // 限制每页最多 20 个
        long pageSize = appQueryRequest.getPageSize();
        ThrowUtils.throwIf(pageSize > 20, ErrorCode.PARAMS_ERROR, "每页最多查询 20 个应用");
        long pageNum = appQueryRequest.getPageNum();
        // 只查询精选的应用
        appQueryRequest.setPriority(AppConstant.GOOD_APP_PRIORITY);
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        // 分页查询
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        // 数据封装
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }

    /**
     * 管理员删除应用
     *
     * @param deleteRequest 删除请求
     * @return 删除结果
     */
    @PostMapping("/admin/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteAppByAdmin(
            @RequestBody DeleteRequest deleteRequest,
            HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long id = deleteRequest.getId();
        User admin = userService.getLoginUser(request);
        boolean result = appService.deleteApp(id, admin);
        return ResultUtils.success(result);
    }

    /**
     * 管理员更新应用
     *
     * @param appAdminUpdateRequest 更新请求
     * @return 更新结果
     */
    @PostMapping("/admin/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateAppByAdmin(@RequestBody AppAdminUpdateRequest appAdminUpdateRequest) {
        if (appAdminUpdateRequest == null || appAdminUpdateRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        long id = appAdminUpdateRequest.getId();
        // 判断是否存在
        App oldApp = appService.getById(id);
        ThrowUtils.throwIf(oldApp == null, ErrorCode.NOT_FOUND_ERROR);
        App app = new App();
        BeanUtil.copyProperties(appAdminUpdateRequest, app);
        // 设置编辑时间
        app.setEditTime(LocalDateTime.now());
        boolean result = appService.updateById(app);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 管理员分页获取应用列表
     *
     * @param appQueryRequest 查询请求
     * @return 应用列表
     */
    @PostMapping("/admin/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<AppVO>> listAppVOByPageByAdmin(@RequestBody AppQueryRequest appQueryRequest) {
        ThrowUtils.throwIf(appQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long pageNum = appQueryRequest.getPageNum();
        long pageSize = appQueryRequest.getPageSize();
        QueryWrapper queryWrapper = appService.getQueryWrapper(appQueryRequest);
        Page<App> appPage = appService.page(Page.of(pageNum, pageSize), queryWrapper);
        // 数据封装
        Page<AppVO> appVOPage = new Page<>(pageNum, pageSize, appPage.getTotalRow());
        List<AppVO> appVOList = appService.getAppVOList(appPage.getRecords());
        appVOPage.setRecords(appVOList);
        return ResultUtils.success(appVOPage);
    }

    /**
     * 管理员根据 id 获取应用详情
     *
     * @param id 应用 id
     * @return 应用详情
     */
    @GetMapping("/admin/get/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<AppVO> getAppVOByIdByAdmin(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 查询数据库
        App app = appService.getById(id);
        ThrowUtils.throwIf(app == null, ErrorCode.NOT_FOUND_ERROR);
        // 获取封装类
        return ResultUtils.success(appService.getAppVO(app));
    }

}
