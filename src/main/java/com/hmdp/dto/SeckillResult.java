package com.hmdp.dto;

import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 秒杀结果DTO
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class SeckillResult implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 请求ID
     */
    private String requestId;
    
    /**
     * 用户ID
     */
    private Long userId;
    
    /**
     * 优惠券ID
     */
    private Long voucherId;
    
    /**
     * 是否成功
     */
    private Boolean success;
    
    /**
     * 订单ID（成功时返回）
     */
    private Long orderId;
    
    /**
     * 错误信息（失败时返回）
     */
    private String errorMessage;
    
    /**
     * 处理时间戳
     */
    private Long processTime;
    
    public SeckillResult(String requestId, Long userId, Long voucherId, Boolean success) {
        this.requestId = requestId;
        this.userId = userId;
        this.voucherId = voucherId;
        this.success = success;
        this.processTime = System.currentTimeMillis();
    }
} 