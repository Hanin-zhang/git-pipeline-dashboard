package com.githublook.webhook.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.githublook.webhook.WebhookDelivery;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface WebhookDeliveryMapper extends BaseMapper<WebhookDelivery> {
}
