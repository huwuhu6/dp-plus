package com.hmdp.cache;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "shop.cache.l1")
public class ShopCacheProperties {
    private boolean enabled = true;
    private long maximumSize = 5_000;
    private long refreshAfterWriteSeconds = 5;
    private long expireAfterWriteSeconds = 30;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getMaximumSize() {
        return maximumSize;
    }

    public void setMaximumSize(long maximumSize) {
        this.maximumSize = maximumSize;
    }

    public long getRefreshAfterWriteSeconds() {
        return refreshAfterWriteSeconds;
    }

    public void setRefreshAfterWriteSeconds(long refreshAfterWriteSeconds) {
        this.refreshAfterWriteSeconds = refreshAfterWriteSeconds;
    }

    public long getExpireAfterWriteSeconds() {
        return expireAfterWriteSeconds;
    }

    public void setExpireAfterWriteSeconds(long expireAfterWriteSeconds) {
        this.expireAfterWriteSeconds = expireAfterWriteSeconds;
    }
}
