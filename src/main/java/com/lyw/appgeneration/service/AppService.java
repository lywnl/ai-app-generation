package com.lyw.appgeneration.service;

import com.lyw.appgeneration.model.dto.app.AppAddRequest;
import com.lyw.appgeneration.model.dto.app.AppDeployRequest;
import com.lyw.appgeneration.model.dto.app.AppQueryRequest;
import com.lyw.appgeneration.model.entity.App;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.vo.app.AppVO;
import com.lyw.appgeneration.core.handler.GenerationStreamEvent;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 应用 服务层。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
public interface AppService extends IService<App> {

    /**
     * 获取应用脱敏数据
     * @param app
     * @return
     */
    AppVO getAppVO(App app);

    /**
     * 获取查询包装类
     * @param appQueryRequest
     * @return
     */
    QueryWrapper getQueryWrapper(AppQueryRequest appQueryRequest);

    /**
     * 获取应用脱敏列表
     * @param appList
     * @return
     */
    List<AppVO> getAppVOList(List<App> appList);

    /**
     * 聊天生成代码
     * @param appId
     * @param messgae
     * @param loginUser
     * @return
     */
    Flux<GenerationStreamEvent> chatToGenCode(
            Long appId, String message, User loginUser);

    /**
     * 应用部署
     * @param appId
     * @param loginUser
     * @return
     */
    String deployApp(Long appId, User loginUser);

    /**
     * 添加应用
     * @param appAddRequest
     * @param loginUser
     * @return
     */
    Long addApp(AppAddRequest appAddRequest, User loginUser);
}
