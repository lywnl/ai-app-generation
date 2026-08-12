package com.lyw.appgeneration.ai;

import java.util.Set;

/** Vue 在线生成与离线评测的不可变工具权限定义。 */
public final class VueToolNames {

    public static final Set<String> ONLINE = Set.of(
            "writeFile", "readFile", "modifyFile", "deleteFile", "readDir",
            "buildProject");
    public static final Set<String> EVALUATION = Set.of(
            "writeFile", "readFile", "modifyFile", "deleteFile", "readDir", "exit");

    private VueToolNames() {
    }
}
