package com.lyw.appgeneration.service;

import com.lyw.appgeneration.model.dto.memory.*;
import com.lyw.appgeneration.model.vo.*;

public interface UserMemoryManagementService {
    UserMemoryPageVO list(long userId, UserMemoryQueryRequest request);
    UserMemoryPreviewVO preview(long userId, UserMemoryPreviewRequest request);
    UserMemoryMutationVO change(long userId, UserMemoryMutationRequest request);
    UserMemoryHistoryVO history(long userId, UserMemoryQueryRequest request);
}
