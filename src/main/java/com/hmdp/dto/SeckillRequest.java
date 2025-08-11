package com.hmdp.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀请求DTO
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SeckillRequest implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 优惠券ID
     */
    private Long voucherId;
    
    /**
     * 请求时间戳
     */
    private Long timestamp;
    
    /**
     * 请求ID，用于去重
     */
    private String requestId;
    
    public SeckillRequest(Long userId, Long voucherId) {
        this.userId = userId;
        this.voucherId = voucherId;
        this.timestamp = System.currentTimeMillis();
        this.requestId = userId + "_" + voucherId + "_" + timestamp;
    }
} 