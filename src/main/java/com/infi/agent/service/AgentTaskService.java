package com.infi.agent.service;

import com.infi.agent.dto.AgentTaskDecisionDTO;
import com.infi.agent.dto.AgentTaskSubmitDTO;
import com.infi.agent.security.UserPrincipal;
import com.infi.agent.vo.AgentTaskVO;
import java.util.List;

/** 当前用户任务的执行、查询和人工确认业务边界。 */
public interface AgentTaskService {
    /**
     * 提交当前用户的智能体任务，返回可供轮询的执行快照。
     *
     * @param user 当前用户
     * @param request 普通任务
     * @return 可轮询任务
     */
    AgentTaskVO submit(UserPrincipal user, AgentTaskSubmitDTO request);

    /**
     * 根据任务编号查询当前用户本人的任务执行快照。
     *
     * @param user 当前用户
     * @param id 任务编号
     * @return 本人任务快照
     */
    AgentTaskVO get(UserPrincipal user, String id);

    /**
     * 查询当前用户所有尚未结束的任务。
     *
     * @param user 当前用户
     * @return 本人未结束任务
     */
    List<AgentTaskVO> active(UserPrincipal user);

    /**
     * 提交当前用户对任务中具体操作的人工决定，返回最新执行快照。
     *
     * @param user 当前用户
     * @param id 任务编号
     * @param decision 针对具体操作的决定
     * @return 最新状态
     */
    AgentTaskVO decide(UserPrincipal user, String id, AgentTaskDecisionDTO decision);
}
