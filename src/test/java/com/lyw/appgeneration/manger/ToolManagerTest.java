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

    @Test
    void rejectsDuplicateToolNamesWithoutSplittingArrayAndMapViews() {
        ToolManager manager = new ToolManager();
        BaseTool[] duplicateTools = toolsWithNames("writeFile", "writeFile");
        ReflectionTestUtils.setField(manager, "tools", duplicateTools);

        assertThrows(IllegalStateException.class, manager::initTools);
        assertArrayEquals(new BaseTool[0], manager.getAllTools());
    }

    private ToolManager managerWith(String... names) {
        BaseTool[] tools = toolsWithNames(names);
        ToolManager manager = new ToolManager();
        ReflectionTestUtils.setField(manager, "tools", tools);
        manager.initTools();
        return manager;
    }

    private BaseTool[] toolsWithNames(String... names) {
        return java.util.Arrays.stream(names).map(name -> {
            BaseTool tool = mock(BaseTool.class);
            when(tool.getToolName()).thenReturn(name);
            when(tool.getDisplayName()).thenReturn(name);
            return tool;
        }).toArray(BaseTool[]::new);
    }
}
