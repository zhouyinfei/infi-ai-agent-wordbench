package com.infi.agent;

import com.infi.agent.config.AiModelProperties;
import com.infi.agent.controller.ModelController;
import com.infi.agent.react.model.ModelCatalog;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import static org.assertj.core.api.Assertions.assertThat;

class ModelPropertiesBindingTest {
    @Test void bracketNotationPreservesDottedModelIdsAndBothModelsAreSelectable() {
        var source = new MapConfigurationPropertySource(Map.of(
                "app.ai.default-model", "deepseek-v4-flash-0731",
                "app.ai.models[deepseek-v4.1-flash].enabled", "true",
                "app.ai.models[deepseek-v4.1-flash].model-name", "deepseek-v4.1-flash",
                "app.ai.models[deepseek-v4.1-flash].api-key", "test-key",
                "app.ai.models.deepseek-v4-flash-0731.enabled", "true",
                "app.ai.models.deepseek-v4-flash-0731.model-name", "deepseek-v4-flash-0731",
                "app.ai.models.deepseek-v4-flash-0731.api-key", "test-key"));
        var properties = new Binder(source).bind("app.ai", Bindable.of(AiModelProperties.class)).get();
        assertThat(properties.getModels()).containsOnlyKeys("deepseek-v4.1-flash", "deepseek-v4-flash-0731");
        assertThat(ModelCatalog.resolve(properties, "deepseek-v4.1-flash").getModelName()).isEqualTo("deepseek-v4.1-flash");
        assertThat(ModelCatalog.resolve(properties, null).getModelName()).isEqualTo("deepseek-v4-flash-0731");
        assertThat(new ModelController(properties).models().data()).hasSize(2);
    }
}
