package com.lyw.appgeneration.service;

import com.lyw.appgeneration.model.dto.app.ChatHistoryQueryRequest;
import com.lyw.appgeneration.model.entity.ChatHistory;
import com.lyw.appgeneration.model.entity.User;
import com.lyw.appgeneration.model.enums.ChatHistoryMessageTypeEnum;
import com.mybatisflex.core.paginate.Page;
import com.mybatisflex.core.query.QueryWrapper;
import com.mybatisflex.core.service.IService;
import dev.langchain4j.memory.ChatMemory;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对话历史 服务层。
 *
 * @author <a href="https://gitee.com/lywynl">lyw</a>
 */
public interface ChatHistoryService extends IService<ChatHistory> {

    /**
     * 添加对话消息到数据库
     * @param appId
     * @param message
     * @param messageType
     * @param userId
     * @return
     */
    boolean addChatMessage(Long appId, String message, String messageType, Long userId);

    /**
     * 根据应用ID删除对话消息
     * @param appId
     * @return
     */
    boolean deleteByAppId(Long appId);

    /**
     * 获取查询条件
     * @param chatHistoryQueryRequest
     * @return
     */
    QueryWrapper getQueryWrapper(ChatHistoryQueryRequest chatHistoryQueryRequest);

    /**
     * 根据应用ID分页获取对话消息
     * @param appId
     * @param pageSize
     * @param lastCreateTime
     * @param loginUser
     * @return
     */
    Page<ChatHistory> listAppChatHistoryByPage(Long appId, int pageSize, LocalDateTime lastCreateTime, User loginUser);

    /**
     * 加载对话历史到内存
     * @param appId
     * @param chatMemory
     * @param maxCount
     * @return
     */
    int loadChatHistoryToMemory(Long appId, ChatMemory chatMemory, int maxCount);

    /**
     * 判断指定应用是否已存在任何对话记录（用于「是否首次对话」判定）
     * @param appId 应用 ID
     * @return 已有记录返回 true
     */
    boolean existsByAppId(Long appId);

    /**
     * 查询 appId 下 id 大于 cursorId 的消息，按 id 正序，最多 limit 条。
     * <p>供 L1 滚动摘要增量提炼：游标之后、热窗口之前的「待折叠」消息。
     *
     * @param appId    应用 ID
     * @param cursorId 游标（已摘要覆盖到的 chat_history.id），null 视为 0
     * @param limit    最多返回条数
     * @return 按 id 正序的消息列表
     */
    List<ChatHistory> listMessagesAfterCursor(Long appId, Long cursorId, int limit);
}
