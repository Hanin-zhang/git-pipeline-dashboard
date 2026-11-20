package com.githublook.support;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MyBatis-Plus 的 {@code LambdaQueryWrapper.eq(Entity::getField, ...)} 在解析列名时
 * 依赖 {@code TableInfoHelper} 里每个实体的 {@code TableInfo} 缓存。这份缓存平时由
 * Spring 启动时扫描 {@code @TableName} 填充，纯单元测试（不启动容器）里是空的，
 * 一旦调用 Lambda 条件就会抛 "can not find lambda cache for this entity"。
 *
 * <p>这里按实体逐个补注册（幂等），让服务层测试可以继续用 Mockito 隔离数据库，
 * 而不必为了一个 where 条件启动整个 Spring 上下文。
 */
public final class MybatisLambdaTestSupport {

    private static final Set<Class<?>> REGISTERED = ConcurrentHashMap.newKeySet();

    private MybatisLambdaTestSupport() {
    }

    public static synchronized void init(Class<?>... entities) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        for (Class<?> entity : entities) {
            if (!REGISTERED.add(entity)) {
                continue;
            }
            MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
            assistant.setCurrentNamespace(entity.getName());
            TableInfoHelper.initTableInfo(assistant, entity);
        }
    }
}
