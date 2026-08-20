CREATE TABLE `tb_voucher_package_order` (
  `id` bigint NOT NULL COMMENT '套餐订单ID',
  `user_id` bigint unsigned NOT NULL COMMENT '购买用户ID',
  `voucher_id` bigint unsigned NOT NULL COMMENT '团购商品ID',
  `shop_id` bigint unsigned NOT NULL COMMENT '履约门店ID',
  `quantity` int NOT NULL COMMENT '购买券张数',
  `paid_amount` bigint NOT NULL COMMENT '实付金额，单位分',
  `status` varchar(24) NOT NULL COMMENT 'PAID/PARTIALLY_USED/USED/PARTIALLY_REFUNDED/REFUNDED',
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  KEY `idx_voucher_package_order_user_time` (`user_id`, `create_time`),
  KEY `idx_voucher_package_order_shop_status` (`shop_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='套餐券订单；状态由券实例聚合得出';

CREATE TABLE `tb_voucher_certificate` (
  `id` bigint NOT NULL COMMENT '券实例ID',
  `package_order_id` bigint NOT NULL COMMENT '套餐订单ID',
  `certificate_no` varchar(64) NOT NULL COMMENT '外部展示券码',
  `shop_id` bigint unsigned NOT NULL COMMENT '允许核销的门店ID',
  `status` varchar(16) NOT NULL COMMENT 'UNUSED/USED/REFUNDED',
  `used_time` datetime NULL,
  `refunded_time` datetime NULL,
  `create_time` datetime NOT NULL,
  `update_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_voucher_certificate_no` (`certificate_no`),
  KEY `idx_voucher_certificate_order_status` (`package_order_id`, `status`),
  KEY `idx_voucher_certificate_shop_status` (`shop_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可独立核销和退款的单张券实例';

CREATE TABLE `tb_voucher_fulfillment_audit` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '审计流水ID',
  `certificate_id` bigint NOT NULL COMMENT '券实例ID',
  `request_id` varchar(64) NOT NULL COMMENT '客户端幂等请求号',
  `action` varchar(16) NOT NULL COMMENT 'VERIFY/REFUND',
  `result` varchar(16) NOT NULL COMMENT 'SUCCESS/REJECTED',
  `operator_type` varchar(16) NOT NULL COMMENT 'MERCHANT/USER',
  `operator_id` bigint NOT NULL COMMENT '操作方ID',
  `detail` varchar(255) NULL COMMENT '失败原因或核销门店等摘要',
  `create_time` datetime NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_voucher_fulfillment_request` (`request_id`),
  KEY `idx_voucher_fulfillment_certificate_time` (`certificate_id`, `create_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='套餐券履约审计；请求号唯一用于重试幂等';
