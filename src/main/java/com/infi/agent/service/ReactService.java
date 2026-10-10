package com.infi.agent.service;

import com.infi.agent.dto.ReactRunDTO;
import com.infi.agent.vo.ReactConfigVO;
import com.infi.agent.vo.ReactRunVO;

/** ReAct 单次执行业务接口。 */
public interface ReactService {
    /**
     * 查询 ReAct 模型可用状态、服务端工具及执行预算。
     *
     * @return 模型、工具与执行预算配置
     */
    ReactConfigVO configuration();
    /**
     * 同步执行单次 ReAct 任务，由模型自动选择注册工具，返回结果与完整执行轨迹。
     *
     * @param userId 目标用户 ID；当前用户接口从认证上下文获取
     * @param request 已通过接口参数校验的请求数据
     * @return 任务执行结果及轨迹
     */
    ReactRunVO execute(Long userId, ReactRunDTO request);
}
