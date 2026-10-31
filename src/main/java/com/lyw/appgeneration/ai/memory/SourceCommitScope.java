package com.lyw.appgeneration.ai.memory;

import dev.langchain4j.service.ModelRequestGate;

/** 把执行代次绑定到实际 L0 追加，回调接收 CAS 成功的确切来源，不能按文字反推序号。 */
public final class SourceCommitScope {
    private static final ThreadLocal<Binding> CURRENT = new ThreadLocal<>();
    private SourceCommitScope() { }

    public static void run(long generation, ModelRequestGate.ContinuationGate gate, Runnable action) {
        Binding previous = CURRENT.get();
        if (generation > 0 && gate instanceof ContextContinuationGate context) CURRENT.set(new Binding(generation, context));
        else CURRENT.remove();
        try {
            action.run();
        } finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
        }
    }

    static void committed(String epoch, L0Snapshot.Message message) {
        Binding binding = CURRENT.get();
        if (binding != null && message.userHistoryId() > 0) {
            binding.gate().sourceMessageCommitted(binding.generation(), epoch, message);
        }
    }

    private record Binding(long generation, ContextContinuationGate gate) { }
}
