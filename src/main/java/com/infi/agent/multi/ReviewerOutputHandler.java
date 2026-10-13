package com.infi.agent.multi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.vo.ReactStepVO;
import lombok.RequiredArgsConstructor;

/** 审校Agent的专业行为：严格检查审校结构并返回显式通过或拒绝状态。 */
@RequiredArgsConstructor
public class ReviewerOutputHandler implements AgentOutputHandler {
    private static final int MAX_REVIEW_LENGTH = 6000;
    private final ObjectMapper objectMapper;

    /**
     * 校验审校JSON，未通过时交付失败状态，不由协调服务重新解释文本。
     *
     * @param task 审校任务
     * @param result 审校模型的基础回复
     * @param context 父执行预算及轨迹
     * @return 显式审校结论
     */
    @Override
    public AgentResult process(AgentTask task, AgentResult result, AgentExecutionContext context) {
        AgentReview review = parse(result.result());
        context.recordStep(new ReactStepVO(0, task.taskNumber(), "REVIEW",
                review.approved() ? "SUCCESS" : "ERROR", review.approved() ? "审校通过" : "审校未通过",
                null, null, review.reason(), 0L, AgentRole.REVIEWER.displayName()));
        if (!review.approved()) {
            return AgentResult.of(task, AgentRole.REVIEWER, "FAILED", "审校未通过：" + review.reason(), result.evidence())
                    .withReview(review);
        }
        return result.withReview(review);
    }

    /**
     * 校验审校字段、类型和非空回答，拒绝额外JSON数据。
     *
     * @param text 审校模型返回文本
     * @return 经校验的审校结论
     */
    private AgentReview parse(String text) {
        if (text.length() > MAX_REVIEW_LENGTH) {
            throw new AgentExecutionException("FAILED", "审校结果过长");
        }
        try {
            JsonNode root = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
            if (root == null || !root.isObject() || root.size() != 3 || !root.path("approved").isBoolean()
                    || !root.path("answer").isTextual() || !root.path("reason").isTextual()
                    || root.path("reason").asText().isBlank()) {
                throw new AgentExecutionException("FAILED", "审校结果格式不合法");
            }
            boolean approved = root.get("approved").asBoolean();
            String answer = root.get("answer").asText();
            if (approved && answer.isBlank()) {
                throw new AgentExecutionException("FAILED", "审校未给出最终回答");
            }
            return new AgentReview(approved, answer, root.get("reason").asText());
        } catch (JsonProcessingException exception) {
            throw new AgentExecutionException("FAILED", "审校结果不是合法JSON");
        }
    }
}
