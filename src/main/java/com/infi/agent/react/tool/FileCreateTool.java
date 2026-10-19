package com.infi.agent.react.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 创建 UTF-8 文本文件，文件已存在时失败。 */
@Component
@RequiredArgsConstructor
public class FileCreateTool implements ReactTool {
    private final ToolFileStore store;

    @Override
    public String execute(JsonNode arguments) {
        ToolArguments.fields(arguments, "path", "content");
        return apply(ToolArguments.text(arguments, "path"), ToolArguments.text(arguments, "content"));
    }

    @Tool(name = "file_create", value = "创建 UTF-8 文本文件，文件已存在时失败；仅允许工具专用目录内的相对路径，所有会话共享此目录。")
    public String apply(@P(name = "path", value = "相对于工具专用目录的路径") String path, @P(name = "content", value = "UTF-8 文本内容，最大 64 KiB") String content) {
        return store.write(path, content, true);
    }
}