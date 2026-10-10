package com.infi.agent.vo;

import java.util.List;

/** 公开运行配置，不返回连接地址或密钥。 */
public record ReactConfigVO(boolean configured, String modelName, int maxIterations,
                            int maxToolCalls, long taskTimeoutSeconds, List<ReactToolVO> tools) {
}
