package com.infi.agent.entity;

import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** 用户持久化对象，仅在数据访问与业务层使用。 */
@Getter
@Setter
public class UserDO {
    private Long id;
    private String username;
    private String passwordHash;
    private String nickname;
    private String email;
    private String role;
    private Integer status;
    private Long authVersion;
    private LocalDateTime gmtCreate;
    private LocalDateTime gmtModified;
}
