USE simlect_order;
CREATE TABLE IF NOT EXISTS after_sale_fulfillment (
 request_id varchar(32) NOT NULL PRIMARY KEY,
 phase varchar(24) NOT NULL DEFAULT 'PENDING_REVIEW',
 review_note varchar(300) NULL,
 return_address varchar(300) NULL,
 return_carrier varchar(50) NULL,
 return_tracking varchar(64) NULL,
 inspection_note varchar(300) NULL,
 restockable tinyint NOT NULL DEFAULT 0,
 refund_amount decimal(10,2) NULL,
 refund_reference varchar(32) NULL,
 simulated tinyint NOT NULL DEFAULT 0,
 updated_at datetime(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
 CONSTRAINT chk_after_sale_phase CHECK (phase IN ('PENDING_REVIEW','WAIT_RETURN','WAIT_INSPECTION','READY_REFUND','REFUNDING','REFUND_FAILED','REJECTED','COMPLETED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE after_sale_log MODIFY COLUMN actor_id varchar(64) NOT NULL;
