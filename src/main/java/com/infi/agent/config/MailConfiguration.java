package com.infi.agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

@Configuration
public class MailConfiguration {
    @Bean
    public JavaMailSender agentMailSender(MailProperties properties) {
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(properties.getHost());
        sender.setPort(properties.getPort());
        sender.setUsername(properties.getUsername());
        sender.setPassword(properties.getPassword());
        sender.setDefaultEncoding("UTF-8");
        var smtp = sender.getJavaMailProperties();
        smtp.setProperty("mail.smtp.auth", Boolean.toString(properties.isAuth()));
        smtp.setProperty("mail.smtp.ssl.enable", Boolean.toString(properties.isSsl()));
        smtp.setProperty("mail.smtp.ssl.checkserveridentity", "true");
        smtp.setProperty("mail.smtp.starttls.enable", Boolean.toString(properties.isStarttls()));
        smtp.setProperty("mail.smtp.starttls.required", Boolean.toString(properties.isStarttls()));
        smtp.setProperty("mail.smtp.connectiontimeout", "10000");
        smtp.setProperty("mail.smtp.timeout", "15000");
        smtp.setProperty("mail.smtp.writetimeout", "15000");
        return sender;
    }
}
