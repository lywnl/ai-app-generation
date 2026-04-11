package com.lyw.appgeneration.service.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.lyw.appgeneration.constants.AppConstant;
import com.lyw.appgeneration.core.AiCodeGeneratorFacade;
import com.lyw.appgeneration.exception.BusinessException;
import com.lyw.appgeneration.exception.ErrorCode;
import com.lyw.appgeneration.exception.ThrowUtils;
import com.lyw.appgeneration.mapper.AppMapper;
import com.lyw.appgeneration.model.dto.app.AppQueryRequest;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.CodeGenTypeEnum;
import com.lyw.appgeneration.model.vo.app.AppVO;
import com.lyw.appgeneration.model.vo.user.UserVO;
import com.lyw.appgeneration.service.AppService;
import com.lyw.appgeneration.service.UserService;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.spring.service.impl.ServiceImpl;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.io.File;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 应用 服务层实现。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
@Service
public class AppServiceImpl extends ServiceImpl<AppMapper, App> implements AppService {

    @Resource
    private AppMapper appMapper;

    @Resource
    private UserService userService;

    @Resource
    private AiCodeGeneratorFacade aiCodeGeneratorFacade;

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
    public Flux<String> chatToGenCode(Long appId, String message, User loginUser) {
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
        // 5. 调用 AI 生成代码
        return aiCodeGeneratorFacade.generateAndSaveCodeStream(message, codeGenTypeEnum, appId);
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
        //7. 拷贝文件到部署目录
        String deployPath = AppConstant.CODE_DEPLOY_ROOT_DIR + File.separator + deployKey;
        try {
            FileUtil.copyContent(file, new File(deployPath), true);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "部署目录拷贝失败: " + e.getMessage());
        }
        //8. 更新数据库
        App updateApp = new App();
        updateApp.setDeployKey(deployKey);
        updateApp.setId(appId);
        updateApp.setDeployedTime(LocalDateTime.now());
        boolean result = updateById(updateApp);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "更新部署失败");

        //9. 返回可访问的地址
        return String.format("%s/%s/", AppConstant.CODE_DEPLOY_HOST, deployKey);

    }


}
