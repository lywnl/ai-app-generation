package com.lyw.appgeneration.manger;

import com.lyw.appgeneration.ai.tools.BaseTool;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ToolManagerTest {

    @Test
    void selectsOnlyExplicitlyAllowedToolsInRequestedOrder() {
        ToolManager manager = managerWith("writeFile", "exit", "buildProject");

        BaseTool[] selected = manager.getTools(Set.of("writeFile", "buildProject"));

        assertArrayEquals(
                new String[]{"writeFile", "buildProject"},
                java.util.Arrays.stream(selected).map(BaseTool::getToolName).toArray(String[]::new));
    }

    @Test
    void rejectsUnknownToolNameInsteadOfSilentlyWeakeningWhitelist() {
        ToolManager manager = managerWith("writeFile", "buildProject");

        assertThrows(IllegalArgumentException.class,
                () -> manager.getTools(Set.of("writeFile", "unknownTool")));
    }

    private ToolManager managerWith(String... names) {
        BaseTool[] tools = java.util.Arrays.stream(names).map(name -> {
            BaseTool tool = mock(BaseTool.class);
            when(tool.getToolName()).thenReturn(name);
            when(tool.getDisplayName()).thenReturn(name);
            return tool;
        }).toArray(BaseTool[]::new);
        ToolManager manager = new ToolManager();
        ReflectionTestUtils.setField(manager, "tools", tools);
        manager.initTools();
        return manager;
    }
}
