package com.infi.agent.mapper;

import com.infi.agent.entity.UserDO;
import java.util.List;
import org.apache.ibatis.annotations.Param;

/** 用户数据访问，只使用绑定参数。 */
public interface UserMapper {
    UserDO selectById(@Param("id") Long id);
    UserDO selectByUsername(@Param("username") String username);
    int insert(UserDO user);
    int updateProfile(@Param("id") Long id, @Param("nickname") String nickname, @Param("email") String email);
    int updatePassword(@Param("id") Long id, @Param("oldHash") String oldHash, @Param("newHash") String newHash);
    int updateStatus(@Param("id") Long id, @Param("status") Integer status);
    List<UserDO> selectPage(@Param("keyword") String keyword, @Param("offset") int offset, @Param("size") int size);
    long count(@Param("keyword") String keyword);
}
