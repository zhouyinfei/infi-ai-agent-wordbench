package com.infi.agent.react.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 永久删除工具目录内的单个文件。 */
@Component
@RequiredArgsConstructor
public class FileDeleteTool implements ReactTool {
    private final ToolFileStore store;

    @Override
    public String execute(JsonNode arguments) {
        ToolArguments.fields(arguments, "path");
        return apply(ToolArguments.text(arguments, "path"));
    }

    @Tool(name = "file_delete", value = "永久删除工具目录内的单个文件；仅允许工具专用目录内的相对路径，所有会话共享此目录。")
    public String apply(@P(name = "path", value = "相对于工具专用目录的路径") String path) {
        return store.delete(path);
    }
}