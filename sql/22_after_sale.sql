-- Additive migration only. Back up before applying to any non-demo database.
USE simlect_order;
CREATE TABLE IF NOT EXISTS after_sale_request (
 request_id varchar(32) NOT NULL PRIMARY KEY,
 user_id varchar(15) NOT NULL,
 order_id varchar(32) NOT NULL,
 order_item_id varchar(40) NOT NULL,
 request_type varchar(20) NOT NULL,
 reason varchar(300) NOT NULL,
 status varchar(24) NOT NULL,
 policy_version varchar(30) NOT NULL,
 created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 active_item_id varchar(40) GENERATED ALWAYS AS (CASE WHEN status IN ('PENDING_REVIEW','APPROVED') THEN order_item_id ELSE NULL END) STORED,
 UNIQUE KEY uk_after_sale_active_item(active_item_id),
 KEY idx_after_sale_user_time(user_id,created_at),
 CONSTRAINT chk_after_sale_type CHECK(request_type IN ('refund','return_refund','exchange')),
 CONSTRAINT chk_after_sale_status CHECK(status IN ('PENDING_REVIEW','APPROVED','REJECTED','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE IF NOT EXISTS after_sale_log (
 log_id bigint NOT NULL AUTO_INCREMENT PRIMARY KEY,
 request_id varchar(32) NOT NULL,
 from_status varchar(24) NOT NULL,
 to_status varchar(24) NOT NULL,
 actor_id varchar(15) NOT NULL,
 remark varchar(300) NOT NULL,
 created_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
 KEY idx_after_sale_log_request(request_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
