package com.infi.agent.service;

import com.infi.agent.dto.LoginDTO;
import com.infi.agent.dto.PasswordDTO;
import com.infi.agent.dto.ProfileDTO;
import com.infi.agent.dto.RegisterDTO;
import com.infi.agent.vo.LoginVO;
import com.infi.agent.vo.PageVO;
import com.infi.agent.vo.UserVO;

/** 用户业务边界。 */
public interface UserService {
    UserVO register(RegisterDTO request);
    LoginVO login(LoginDTO request, String remoteAddress);
    UserVO getProfile(Long userId);
    UserVO updateProfile(Long userId, ProfileDTO request);
    void changePassword(Long userId, PasswordDTO request);
    PageVO<UserVO> listUsers(int page, int size, String keyword);
    void updateStatus(Long userId, Integer status);
    void bootstrapAdmin(String username, String password);
}
