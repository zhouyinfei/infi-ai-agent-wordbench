package com.infi.agent.service;

import com.infi.agent.dto.MultiAgentRunDTO;
import com.infi.agent.vo.ReactConfigVO;
import com.infi.agent.vo.ReactRunVO;

/** 协调、专业执行和独立审校的协作业务边界。 */
public interface MultiAgentService {
    /** @return 协作模式的公开执行预算和模型状态 */
    ReactConfigVO configuration();

    /**
     * 执行单次同步协作，角色之间只传递任务结果和证据。
     *
     * @param userId 来自认证上下文的用户 ID
     * @param request 已校验的任务目标
     * @return 最终结果、角色轨迹和累计Token用量
     */
    ReactRunVO execute(Long userId, MultiAgentRunDTO request);
}
