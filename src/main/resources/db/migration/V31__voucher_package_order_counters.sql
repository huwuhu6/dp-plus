ALTER TABLE tb_voucher_package_order
    ADD COLUMN used_count INT NOT NULL DEFAULT 0 COMMENT '已核销券张数' AFTER quantity,
    ADD COLUMN refunded_count INT NOT NULL DEFAULT 0 COMMENT '已退款券张数' AFTER used_count;

UPDATE tb_voucher_package_order package_order
LEFT JOIN (
    SELECT package_order_id,
           SUM(CASE WHEN status = 'USED' THEN 1 ELSE 0 END) AS used_count,
           SUM(CASE WHEN status = 'REFUNDED' THEN 1 ELSE 0 END) AS refunded_count
    FROM tb_voucher_certificate
    GROUP BY package_order_id
) certificate_counts ON certificate_counts.package_order_id = package_order.id
SET package_order.used_count = COALESCE(certificate_counts.used_count, 0),
    package_order.refunded_count = COALESCE(certificate_counts.refunded_count, 0);
