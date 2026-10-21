package com.infi.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.config.RagProperties;
import com.infi.agent.rag.RagInfrastructure;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.FileSystemResource;
import static org.assertj.core.api.Assertions.assertThat;

/** 显式启用时验证本地实际配置：桶及索引初始化和只读查询，不调用 Embedding。 */
@EnabledIfEnvironmentVariable(named = "RAG_LOCAL_CONNECTION_TEST", matches = "true")
class RagLocalConnectionIT {
    @Test
    void localConfigurationCanInitializeAndQueryKnowledgeIndex() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (String filename : new String[]{"application.yml", "application-local.yml"}) {
            for (var source : loader.load(filename, new FileSystemResource("src/main/resources/" + filename))) {
                environment.getPropertySources().addFirst(source);
            }
        }
        RagProperties properties = Binder.get(environment).bind("app.rag", Bindable.of(RagProperties.class)).get();
        RagInfrastructure infrastructure = new RagInfrastructure(properties, new ObjectMapper());
        infrastructure.initialize();
        assertThat(infrastructure.elastic("POST", "/_search", Map.of("size", 0)).has("hits")).isTrue();
    }
}
