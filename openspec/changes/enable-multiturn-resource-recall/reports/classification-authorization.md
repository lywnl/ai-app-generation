# 真实分类对照授权

2026-11-02 用户针对本轮 request_user_input_async 明确回复：“允许，按这 60 条样例和 240 次上限执行”。

范围：现有 DeepSeek deepseek-flash，temperature=0、maxTokens=256；60条合成样例旧新各两轮，最多240次调用；无重试、无真实聊天上传、无RAG/图片/Logo调用。此前分类脚本替身测试已通过。

授权时输入SHA256：

- src/test/resources/vue-turn-routing/cases.json：`f3834d4a1bc3179d4c495956994633b06faf3e0222806528b5fcff2e198abea3`
- src/test/java/com/lyw/appgeneration/service/VueTurnRoutingComparisonRunner.java：`6fa9fb8e760d7600dace7b358b7609f27a5a0600b7f7bf32fb4d793c0268c566`
- src/test/resources/vue-turn-routing/baseline-system-prompt.txt：`54b6a519455a14270bf745f9bd73fa0462738f113cd57dc42d8970a66920acc7`
- src/main/resources/prompt/vue-turn-mode-routing-system-prompt.txt：`23db0a745a5d83bb3bf8890504bca75d28e647925a5177722b0e6c1bc0937923`

首次进程启动在读取 DEEPSEEK_API_KEY 环境占位符时退出，尚未构造模型客户端，实际外部调用为0。已补充读取项目现有 dev/.env 的单个凭据变量，并保留 startup-failure 记录；这是本地环境修复，240次外部调用额度尚未使用。

第二次本地启动遇到 Maven 测试重编译清理 class 目录，主类尚未加载，因此仍为0次外部调用。已将编译通过的评测类与资源复制到本变更独立 classification-build 并记录SHA256，避免后续编译影响运行。保留 classpath-failure 记录。

最终执行已完成：2026-11-02 21:31:48—21:34:49（Asia/Shanghai），实际240次，无重试、无额外补跑，退出码0。旧/新各120次，两类模式误判和非法输出均为0；新版RAG/图片标记错误和关键否定新增误判均为0。输入哈希与授权样例、当前提示词保持一致。见 [执行记录](../../../../.codex/enable-multiturn-resource-recall/classification-command.json)、[汇总](../../../../.codex/enable-multiturn-resource-recall/classification/summary.json) 和 [逐条观测](../../../../.codex/enable-multiturn-resource-recall/classification/observations.jsonl)。前述“额度尚未使用”仅描述第一次失败时点，不代表仍有可自动追加的额度。
