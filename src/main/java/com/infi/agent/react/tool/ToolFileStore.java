package com.infi.agent.react.tool;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 所有会话共享的工具专用目录；拒绝越界路径、符号链接和递归删除。 */
@Component
public class ToolFileStore {
    static final int MAX_BYTES = 64 * 1024;
    private final Path root;

    public ToolFileStore(@Value("${app.tools.files.root:./tool-workspace}") String root) {
        this.root = Path.of(root).toAbsolutePath().normalize();
    }

    private Path resolve(String input, boolean directory) throws IOException {
        if (input == null || input.isBlank() || input.length() > 512 || input.indexOf(':') >= 0
                || Path.of(input).isAbsolute()) {
            throw new IllegalArgumentException("请提供工具目录内的相对路径");
        }
        Path target = root.resolve(input).normalize();
        if (!target.startsWith(root) || (!directory && target.equals(root))) {
            throw new IllegalArgumentException("路径超出工具目录或指向根目录");
        }
        // 同时检查根目录的祖先，防止配置路径经过符号链接或 Windows junction。
        for (Path part = target; part != null; part = part.getParent()) {
            if (Files.exists(part, LinkOption.NOFOLLOW_LINKS)) {
                var attributes = Files.readAttributes(part, java.nio.file.attribute.BasicFileAttributes.class,
                        LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther()) {
                    throw new IllegalArgumentException("不允许通过链接访问文件");
                }
            }
        }
        return target;
    }

    public synchronized String read(String path) {
        try {
            Path target = resolve(path, false);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("文件不存在或不是普通文件");
            }
            try (InputStream input = Files.newInputStream(target)) {
                byte[] bytes = input.readNBytes(MAX_BYTES + 1);
                if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("文件超过 64 KiB");
                return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString();
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("读取失败，请检查路径、权限及 UTF-8 编码");
        }
    }

    public synchronized String write(String path, String content, boolean create) {
        if (content == null || content.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("内容须为 UTF-8 文本且不超过 64 KiB");
        }
        try {
            Path target = resolve(path, false);
            if (create) {
                Files.createDirectories(target.getParent());
                Files.writeString(target, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            } else {
                if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalArgumentException("待写入文件不存在或不是普通文件");
                }
                Files.writeString(target, content, StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            }
            return create ? "文件已创建: " + path : "文件已写入: " + path;
        } catch (IOException exception) {
            throw new IllegalArgumentException("写入失败，请检查路径、权限及文件是否已存在");
        }
    }

    public synchronized String delete(String path) {
        try {
            Path target = resolve(path, false);
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("仅支持删除已存在的普通文件");
            }
            Files.delete(target);
            return "文件已删除: " + path;
        } catch (IOException exception) {
            throw new IllegalArgumentException("删除失败，请检查路径及权限");
        }
    }

    public synchronized String list(String path) {
        try {
            Path target = resolve(path, true);
            if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException("目录不存在");
            }
            try (var entries = Files.list(target)) {
                var names = entries.map(p -> p.getFileName().toString()
                        + (Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS) ? "/" : ""))
                        .sorted().limit(201).toList();
                return String.join("\n", names.subList(0, Math.min(200, names.size())))
                        + (names.size() > 200 ? "\n（仅显示前 200 项）" : "");
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("列目录失败，请检查路径及权限");
        }
    }
}
