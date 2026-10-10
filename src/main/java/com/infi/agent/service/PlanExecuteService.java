package com.infi.agent.service;

import com.infi.agent.dto.PlanExecuteRunDTO;
import com.infi.agent.vo.ReactRunVO;

/** 先生成计划、逐步执行并汇总的同步业务边界。 */
public interface PlanExecuteService {
    /**
     * 执行单次计划任务，所有阶段共用执行预算。
     *
     * @param userId 来自认证上下文的用户 ID
     * @param request 已校验的任务目标
     * @return 最终结果及计划、步骤和工具轨迹
     */
    ReactRunVO execute(Long userId, PlanExecuteRunDTO request);
}
