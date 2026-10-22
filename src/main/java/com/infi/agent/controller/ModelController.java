package com.infi.agent.controller;

import com.infi.agent.common.ApiResponse;
import com.infi.agent.config.AiModelProperties;
import com.infi.agent.react.model.ModelCatalog;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ModelController {
    private final AiModelProperties properties;
    public record ModelView(String id, String modelName, String provider, boolean configured, boolean defaultModel) {}
    @GetMapping("/api/react/models")
    public ApiResponse<List<ModelView>> models() {
        List<ModelView> result = new ArrayList<>();
        if (properties.getDefaultModel() == null || properties.getDefaultModel().isBlank()) {
            result.add(new ModelView("", properties.getModelName(), properties.getProvider(), ModelCatalog.configured(properties), true));
        }
        properties.getModels().forEach((id, model) -> result.add(new ModelView(id, model.getModelName(),
                model.getProvider(), ModelCatalog.configured(model), id.equals(properties.getDefaultModel()))));
        return ApiResponse.success(result);
    }
}
