package com.infi.agent;

import com.infi.agent.react.model.TokenUsageTracker;
import dev.langchain4j.model.output.TokenUsage;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 验证模型用量累加、缺失值和部分统计不会被误报为完整消耗。 */
class TokenUsageTrackerTest {
    @Test
    void sumsAllReportedCalls() {
        TokenUsageTracker tracker = new TokenUsageTracker();
        tracker.add(new TokenUsage(10, 5, 15));
        tracker.add(new TokenUsage(20, 8, 28));
        var usage = tracker.snapshot(2);
        assertThat(usage.inputTokens()).isEqualTo(30L);
        assertThat(usage.outputTokens()).isEqualTo(13L);
        assertThat(usage.totalTokens()).isEqualTo(43L);
        assertThat(usage.reportedModelCalls()).isEqualTo(2);
        assertThat(usage.complete()).isTrue();
    }

    @Test
    void missingUsageStaysUnknown() {
        TokenUsageTracker tracker = new TokenUsageTracker();
        tracker.add(null);
        tracker.add(new TokenUsage(null, null, null));
        var usage = tracker.snapshot(2);
        assertThat(usage.inputTokens()).isNull();
        assertThat(usage.outputTokens()).isNull();
        assertThat(usage.totalTokens()).isNull();
        assertThat(usage.reportedModelCalls()).isZero();
        assertThat(usage.complete()).isFalse();
    }

    @Test
    void failedOrUnreportedCallMakesAggregatePartial() {
        TokenUsageTracker tracker = new TokenUsageTracker();
        tracker.add(new TokenUsage(10, 5, 15));
        var usage = tracker.snapshot(2);
        assertThat(usage.totalTokens()).isEqualTo(15L);
        assertThat(usage.reportedModelCalls()).isEqualTo(1);
        assertThat(usage.complete()).isFalse();
    }

    @Test
    void totalOnlyDoesNotInventInputAndOutputCounts() {
        TokenUsageTracker tracker = new TokenUsageTracker();
        tracker.add(new TokenUsage(null, null, 9));
        var usage = tracker.snapshot(1);
        assertThat(usage.inputTokens()).isNull();
        assertThat(usage.outputTokens()).isNull();
        assertThat(usage.totalTokens()).isEqualTo(9L);
        assertThat(usage.reportedModelCalls()).isEqualTo(1);
        assertThat(usage.complete()).isFalse();
    }

    @Test
    void missingTotalCanBeDerivedFromReportedInputAndOutput() {
        TokenUsageTracker tracker = new TokenUsageTracker();
        tracker.add(new TokenUsage(10, 5, null));
        assertThat(tracker.snapshot(1).totalTokens()).isEqualTo(15L);
        assertThat(tracker.snapshot(1).complete()).isTrue();
    }

    @Test
    void zeroCallsMeansZeroConsumption() {
        var usage = new TokenUsageTracker().snapshot(0);
        assertThat(usage.inputTokens()).isZero();
        assertThat(usage.outputTokens()).isZero();
        assertThat(usage.totalTokens()).isZero();
        assertThat(usage.complete()).isTrue();
    }

    @Test
    void aggregatesUseLongAndRejectNegativeCounts() {
        TokenUsageTracker tracker = new TokenUsageTracker();
        tracker.add(new TokenUsage(Integer.MAX_VALUE, 1, null));
        tracker.add(new TokenUsage(Integer.MAX_VALUE, 1, null));
        assertThat(tracker.snapshot(2).inputTokens()).isEqualTo(4294967294L);
        assertThat(tracker.snapshot(2).totalTokens()).isEqualTo(4294967296L);
        TokenUsageTracker invalid = new TokenUsageTracker();
        invalid.add(new TokenUsage(-1, -2, -3));
        assertThat(invalid.snapshot(1).totalTokens()).isNull();
        assertThat(invalid.snapshot(1).reportedModelCalls()).isZero();
    }
}
