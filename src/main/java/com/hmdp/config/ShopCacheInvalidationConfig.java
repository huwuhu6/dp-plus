package com.hmdp.config;

import com.hmdp.cache.ShopCacheInvalidationListener;
import com.hmdp.cache.ShopCacheInvalidationPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class ShopCacheInvalidationConfig {
    @Bean
    public RedisMessageListenerContainer shopCacheInvalidationListenerContainer(
            RedisConnectionFactory connectionFactory, ShopCacheInvalidationListener listener) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new ChannelTopic(ShopCacheInvalidationPublisher.CHANNEL));
        return container;
    }
}
