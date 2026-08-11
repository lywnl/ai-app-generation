package com.lyw.appgeneration.manger;

import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AppFileStateManagerTest {

    @Test
    void failedFirstWritesDoNotRetainEmptyAppStates() {
        AppFileStateManager manager = new AppFileStateManager();

        for (long appId = 1; appId <= 20; appId++) {
            long currentAppId = appId;
            assertThrows(IOException.class, () -> manager.writeAndRecord(
                    currentAppId,
                    "failed.txt",
                    "内容",
                    () -> {
                        throw new IOException("模拟写入失败");
                    }));
        }

        assertEquals(0, manager.trackedAppCount());
    }
}
