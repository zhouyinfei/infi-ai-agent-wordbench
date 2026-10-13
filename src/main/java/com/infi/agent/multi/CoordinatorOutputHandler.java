package com.infi.agent.multi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.vo.ReactStepVO;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** 协调Agent的专业行为：将模型输出校验为有序委派，不执行专业工具。 */
@RequiredArgsConstructor
public class CoordinatorOutputHandler implements AgentOutputHandler {
    private static final int MAX_PLAN_LENGTH = 6000;
    private static final int MAX_GOAL_LENGTH = 500;
    private final ObjectMapper objectMapper;

    /**
     * 校验协调交付并记录分工。
     *
     * @param task 协调任务
     * @param result 协调模型的基础结果
     * @param context 父预算和公开轨迹
     * @return 带有结构化委派的结果
     */
    @Override
    public AgentResult process(AgentTask task, AgentResult result, AgentExecutionContext context) {
        List<AgentAssignment> assignments = parse(result.result(), context.maxAssignments());
        context.recordStep(new ReactStepVO(0, task.taskNumber(), "COORDINATE", "SUCCESS",
                "协作分工", null, objectMapper.valueToTree(assignments), null, 0L,
                AgentRole.COORDINATOR.displayName()));
        return result.withAssignments(assignments);
    }

    /**
     * 拒绝未知角色、递归分工、额外字段及不符合预算的目标。
     *
     * @param text 协调模型返回的JSON文本
     * @param maxTasks 当前预算允许的最多任务数
     * @return 不可修改的角色委派列表
     */
    private List<AgentAssignment> parse(String text, int maxTasks) {
        if (text.length() > MAX_PLAN_LENGTH) {
            throw new AgentExecutionException("FAILED", "模型返回的分工过长");
        }
        try {
            JsonNode root = objectMapper.reader().with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).readTree(text);
            if (root == null || !root.isObject() || root.size() != 1 || !root.path("tasks").isArray()) {
                throw new AgentExecutionException("FAILED", "分工格式不合法，须返回仅包含tasks数组的JSON对象");
            }
            JsonNode nodes = root.get("tasks");
            if (nodes.isEmpty() || nodes.size() > maxTasks) {
                throw new AgentExecutionException("FAILED", "分工任务为空或超过允许数量");
            }
            List<AgentAssignment> assignments = new ArrayList<>();
            for (JsonNode node : nodes) {
                if (!node.isObject() || node.size() != 2 || !node.path("agent").isTextual()
                        || !node.path("goal").isTextual() || node.path("goal").asText().isBlank()
                        || node.path("goal").asText().length() > MAX_GOAL_LENGTH) {
                    throw new AgentExecutionException("FAILED", "协作任务须包含有效的agent和goal");
                }
                AgentRole role;
                try {
                    role = AgentRole.valueOf(node.get("agent").asText());
                } catch (IllegalArgumentException exception) {
                    throw new AgentExecutionException("FAILED", "协调者指定了未知Agent");
                }
                if (!role.isWorker()) {
                    throw new AgentExecutionException("FAILED", "禁止向协调者或审校者递归分配任务");
                }
                assignments.add(new AgentAssignment(role, node.get("goal").asText().trim()));
            }
            return List.copyOf(assignments);
        } catch (JsonProcessingException exception) {
            throw new AgentExecutionException("FAILED", "模型返回的分工不是合法JSON");
        }
    }
}
