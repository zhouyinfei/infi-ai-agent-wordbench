package com.infi.agent.service;

import com.infi.agent.dto.ReactRunDTO;
import com.infi.agent.vo.ReactConfigVO;
import com.infi.agent.vo.ReactRunVO;

/** ReAct 单次执行业务接口。 */
public interface ReactService {
    ReactConfigVO configuration();
    ReactRunVO execute(Long userId, ReactRunDTO request);
}
