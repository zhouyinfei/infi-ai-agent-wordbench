package com.infi.agent.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** SMTP 凭证仅由服务端配置，不接受模型传入。 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "app.tools.mail")
public class MailProperties {
    private boolean enabled;
    private String host = "";
    private int port = 587;
    private String username = "";
    private String password = "";
    private String from = "";
    private boolean auth = true;
    private boolean ssl;
    private boolean starttls = true;
}
