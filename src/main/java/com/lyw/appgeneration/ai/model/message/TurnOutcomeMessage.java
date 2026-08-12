package com.lyw.appgeneration.ai.model.message;

import com.lyw.appgeneration.core.builder.VueBuildPhase;
import com.lyw.appgeneration.core.handler.VueTurnOutcome;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

/** 所有正文和工具状态之后发送的 Vue 回合终态控制消息。 */
@Data
@NoArgsConstructor
@EqualsAndHashCode(callSuper = true)
public final class TurnOutcomeMessage extends StreamMessage {

    private VueBuildPhase phase;
    private VueTurnOutcome.TurnOutcomeType outcome;
    private boolean shouldRefreshPreview;
    private String message;

    public TurnOutcomeMessage(VueTurnOutcome turnOutcome) {
        super(StreamMessageTypeEnum.TURN_OUTCOME.getValue());
        this.phase = turnOutcome.phase();
        this.outcome = turnOutcome.outcome();
        this.shouldRefreshPreview = turnOutcome.shouldRefreshPreview();
        this.message = turnOutcome.clientMessage();
    }
}
