package com.infi.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infi.agent.config.MailProperties;
import com.infi.agent.react.tool.SendEmailTool;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SendEmailToolTest {
    private final MailProperties properties = new MailProperties();
    private final JavaMailSender sender = mock(JavaMailSender.class);
    private final SendEmailTool tool = new SendEmailTool(properties, sender);

    private void enable() {
        properties.setEnabled(true);
        properties.setHost("smtp.example.com");
        properties.setFrom("agent@example.com");
    }

    @Test void sendsChinesePlainTextWithConfiguredSender() throws Exception {
        enable();
        assertThat(tool.execute(new ObjectMapper().readTree(
                "{\"to\":\"user@example.com\",\"subject\":\"会议通知\",\"body\":\"明天上午开会\\n请准时参加\"}")))
                .contains("SMTP服务器已接受");
        var message = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(sender).send(message.capture());
        assertThat(message.getValue().getFrom()).isEqualTo("agent@example.com");
        assertThat(message.getValue().getTo()).containsExactly("user@example.com");
        assertThat(message.getValue().getSubject()).isEqualTo("会议通知");
        assertThat(message.getValue().getText()).isEqualTo("明天上午开会\n请准时参加");
        assertThat(tool.specification().name()).isEqualTo("send_email");
    }

    @Test void disabledToolNeverContactsSmtp() {
        assertThatThrownBy(() -> tool.send("user@example.com", "主题", "内容")).hasMessageContaining("未启用");
        verifyNoInteractions(sender);
    }

    @Test void rejectsModelSuppliedSenderAndUnknownFields() throws Exception {
        enable();
        var arguments = new ObjectMapper().readTree(
                "{\"to\":\"user@example.com\",\"subject\":\"主题\",\"body\":\"内容\",\"from\":\"other@example.com\"}");
        assertThatThrownBy(() -> tool.execute(arguments)).hasMessageContaining("未知参数");
        verifyNoInteractions(sender);
        assertThat(com.infi.agent.multi.AgentRole.EMAIL.isWorker()).isTrue();
        assertThat(com.infi.agent.multi.AgentRole.EMAIL.allowsTool("send_email")).isTrue();
        assertThat(com.infi.agent.multi.AgentRole.ANALYST.allowsTool("send_email")).isFalse();
    }

    @Test void rejectsInvalidHeadersMultipleRecipientsAndOversizeBody() {
        enable();
        for (String address : new String[]{"bad", "a@example.com,b@example.com", "a@example.com\r\nBcc: b@example.com"}) {
            assertThatThrownBy(() -> tool.send(address, "主题", "内容")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> tool.send("a@example.com", "主题\n注入", "内容")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool.send("a@example.com", "主题", "中".repeat(22000))).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(sender);
    }

    @Test void failuresAreSanitizedAndNotRetried() {
        enable();
        doThrow(new MailSendException("secret-password")).when(sender).send(any(SimpleMailMessage.class));
        assertThatThrownBy(() -> tool.send("a@example.com", "主题", "内容"))
                .hasMessageContaining("结果不明").hasMessageNotContaining("secret-password");
        verify(sender, times(1)).send(any(SimpleMailMessage.class));
    }
}
