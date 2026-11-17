package com.githublook.auth.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.githublook.auth.AppUser;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AppUserMapper extends BaseMapper<AppUser> {
}
