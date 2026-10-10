package com.infi.agent.react.model;

import com.infi.agent.vo.TokenUsageVO;
import dev.langchain4j.model.output.TokenUsage;

/** 每次执行独享的 token 累加器，只累计模型服务实际返回的数据，不估算缺失消耗。 */
public class TokenUsageTracker {
    private Long inputTokens;
    private Long outputTokens;
    private Long totalTokens;
    private int reportedCalls;
    private int completeCalls;

    /**
     * 累计单次模型回复的用量；无用量不记为零，负值按缺失处理。
     *
     * @param usage LangChain4j 模型回复中的 token 用量，可为 null
     */
    public void add(TokenUsage usage) {
        if (usage == null) {
            return;
        }
        Long input = validCount(usage.inputTokenCount());
        Long output = validCount(usage.outputTokenCount());
        Long total = validCount(usage.totalTokenCount());
        if (total == null && input != null && output != null) {
            total = input + output;
        }
        if (input == null && output == null && total == null) {
            return;
        }
        reportedCalls++;
        if (input != null && output != null && total != null) {
            completeCalls++;
        }
        inputTokens = sumReported(inputTokens, input);
        outputTokens = sumReported(outputTokens, output);
        totalTokens = sumReported(totalTokens, total);
    }

    /**
     * 返回累计用量及完整性，未调用模型时明确返回零。
     *
     * @param modelCalls 本次执行尝试的全部模型调用次数，包括失败调用
     * @return 已报告的用量快照；缺失字段保持 null
     */
    public TokenUsageVO snapshot(int modelCalls) {
        if (modelCalls == 0) {
            return new TokenUsageVO(0L, 0L, 0L, 0, true);
        }
        return new TokenUsageVO(inputTokens, outputTokens, totalTokens,
                reportedCalls, completeCalls == modelCalls);
    }

    /**
     * 将非负服务端计数转换为 Long，避免多轮累计的 int 溢出。
     *
     * @param value 服务端计数
     * @return 有效计数，缺失或负值返回 null
     */
    private Long validCount(Integer value) {
        return value == null || value < 0 ? null : value.longValue();
    }

    /**
     * 累加已报告字段，无数据时保留未知状态。
     *
     * @param previous 之前的累计计数
     * @param current 当前调用返回的计数
     * @return 累计计数；没有任何数据时为 null
     */
    private Long sumReported(Long previous, Long current) {
        if (current == null) {
            return previous;
        }
        return previous == null ? current : previous + current;
    }
}
