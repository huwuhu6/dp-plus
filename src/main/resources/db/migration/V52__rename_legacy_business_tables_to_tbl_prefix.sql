-- Keep Flyway's own schema history table unchanged. All application tables use tbl_.
RENAME TABLE
  `tb_blog` TO `tbl_blog`,
  `tb_blog_comments` TO `tbl_blog_comments`,
  `tb_follow` TO `tbl_follow`,
  `tb_seckill_voucher` TO `tbl_seckill_voucher`,
  `tb_shop` TO `tbl_shop`,
  `tb_shop_type` TO `tbl_shop_type`,
  `tb_sign` TO `tbl_sign`,
  `tb_user` TO `tbl_user`,
  `tb_user_info` TO `tbl_user_info`,
  `tb_voucher` TO `tbl_voucher`,
  `tb_voucher_order` TO `tbl_voucher_order`;
