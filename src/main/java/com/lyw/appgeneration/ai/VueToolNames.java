package com.lyw.appgeneration.ai;

import java.util.List;

/** Vue 在线生成与离线评测的不可变工具权限定义。 */
public final class VueToolNames {

    public static final List<String> ONLINE = List.of(
            "writeFile", "readFile", "modifyFile", "deleteFile", "readDir",
            "buildProject");
    public static final List<String> EVALUATION = List.of(
            "writeFile", "readFile", "modifyFile", "deleteFile", "readDir", "exit");

    private VueToolNames() {
    }
}
