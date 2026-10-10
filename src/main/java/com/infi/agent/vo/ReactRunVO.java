package com.infi.agent.vo;

import java.util.List;

/** 单次执行结果；限制终止和失败同样保留已完成的轨迹。 */
public record ReactRunVO(String runId, String status, String answer, String stopReason,
                         int modelCalls, int toolCalls, long durationMs, List<ReactStepVO> steps) {
}
