package com.infi.agent.vo;

import com.infi.agent.entity.UserDO;
import java.time.LocalDateTime;

/** 用户输出，永不包含密码摘要或凭证版本。 */
public record UserVO(Long id, String username, String nickname, String email,
                     String role, Integer status, LocalDateTime gmtCreate) {
    public static UserVO from(UserDO user) {
        return new UserVO(user.getId(), user.getUsername(), user.getNickname(),
                user.getEmail(), user.getRole(), user.getStatus(), user.getGmtCreate());
    }
}
