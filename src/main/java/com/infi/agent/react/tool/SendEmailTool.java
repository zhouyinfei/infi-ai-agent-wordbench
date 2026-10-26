package com.infi.agent.react.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.infi.agent.config.MailProperties;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

/** 邮件发送是顺序执行的外部副作用工具，不自动重试。 */
@Component
@RequiredArgsConstructor
public class SendEmailTool implements ReactTool {
    private final MailProperties properties;
    private final JavaMailSender sender;

    @Override
    public String execute(JsonNode arguments) {
        ToolArguments.fields(arguments, "to", "subject", "body");
        return send(ToolArguments.text(arguments, "to"), ToolArguments.text(arguments, "subject"),
                ToolArguments.text(arguments, "body"));
    }

    @Tool(name = "send_email", value = "发送一封纯文本邮件给单个收件人。仅按用户明确的发送意图操作，缺少地址或正文先询问；需要确认时通过request_approval提交完整参数。成功仅表示SMTP接受，不保证最终投递；结果不明时不要自动重试。")
    public String send(@P("单个收件人邮箱地址") String to, @P("邮件主题，最多200字符") String subject,
                       @P("纯文本正文，最多64 KiB UTF-8") String body) {
        if (!properties.isEnabled()) throw new IllegalArgumentException("邮件发送未启用，请联系管理员配置SMTP");
        if (properties.getHost() == null || properties.getHost().isBlank()) {
            throw new IllegalArgumentException("SMTP服务器未配置");
        }
        String recipient = address(to);
        String from = address(properties.getFrom());
        if (subject == null || subject.isBlank() || subject.length() > 200
                || subject.contains("\r") || subject.contains("\n")) {
            throw new IllegalArgumentException("邮件主题不能为空、超过200字符或包含换行");
        }
        if (body == null || body.isBlank() || body.getBytes(StandardCharsets.UTF_8).length > 65_536) {
            throw new IllegalArgumentException("邮件正文不能为空或超过64 KiB");
        }
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(recipient);
        message.setSubject(subject);
        message.setText(body);
        try {
            sender.send(message);
        } catch (MailException exception) {
            // 服务端响应可能包含凭证、地址或正文，不暴露给模型或日志。
            throw new IllegalArgumentException("邮件发送失败或结果不明，请核查SMTP服务记录，勿自动重试");
        }
        return "SMTP服务器已接受邮件，不代表已投递到收件箱";
    }

    private static String address(String value) {
        if (value == null || value.isBlank() || value.length() > 254 || value.contains("\r") || value.contains("\n")) {
            throw new IllegalArgumentException("邮箱地址无效");
        }
        try {
            InternetAddress parsed = new InternetAddress(value, true);
            parsed.validate();
            if (parsed.getPersonal() != null || !value.equals(parsed.getAddress()) || !value.contains("@")) {
                throw new AddressException();
            }
            return parsed.getAddress();
        } catch (AddressException exception) {
            throw new IllegalArgumentException("须提供单个有效邮箱地址，不支持显示名称或地址列表");
        }
    }
}
