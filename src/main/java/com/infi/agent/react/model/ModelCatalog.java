package com.infi.agent.react.model;

import com.infi.agent.config.AiModelProperties;
import com.infi.agent.common.BusinessException;
import org.springframework.http.HttpStatus;

/** 服务端模型目录；连接地址和凭证始终留在服务端。 */
public final class ModelCatalog {
    private ModelCatalog() {}
    public static AiModelProperties resolve(AiModelProperties root, String id) {
        String selected = id == null || id.isBlank() ? root.getDefaultModel() : id;
        if (selected == null || selected.isBlank()) return root;
        AiModelProperties model = root.getModels().get(selected);
        if (model == null) throw new BusinessException(HttpStatus.BAD_REQUEST, "INVALID_MODEL", "所选模型不存在");
        if (!configured(model)) throw new BusinessException(HttpStatus.BAD_REQUEST, "MODEL_UNAVAILABLE", "所选模型不可用");
        return model;
    }
    public static boolean configured(AiModelProperties model) {
        return model.isEnabled() && "openai-compatible".equals(model.getProvider())
                && present(model.getApiKey()) && present(model.getBaseUrl()) && present(model.getModelName())
                && model.getRequestTimeout() != null && !model.getRequestTimeout().isZero()
                && !model.getRequestTimeout().isNegative();
    }
    private static boolean present(String value) { return value != null && !value.isBlank(); }
}
