package com.infi.agent.react.tool;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 覆盖写入已存在的 UTF-8 文本文件。 */
@Component
@RequiredArgsConstructor
public class FileWriteTool implements ReactTool {
    private final ToolFileStore store;

    @Override
    public String execute(JsonNode arguments) {
        ToolArguments.fields(arguments, "path", "content");
        return apply(ToolArguments.text(arguments, "path"), ToolArguments.text(arguments, "content"));
    }

    @Tool(name = "file_write", value = "覆盖写入已存在的 UTF-8 文本文件；仅允许工具专用目录内的相对路径，所有会话共享此目录。")
    public String apply(@P(name = "path", value = "相对于工具专用目录的路径") String path, @P(name = "content", value = "UTF-8 文本内容，最大 64 KiB") String content) {
        return store.write(path, content, false);
    }
}