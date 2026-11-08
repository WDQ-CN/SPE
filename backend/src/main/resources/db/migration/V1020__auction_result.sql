-- E2 竞价结果与转 PO（docs/erp/55 §4.4、56 §6.4）。
-- 只增不改：应用后本文件冻结（06 §1.3）。

CREATE TABLE auction_result (
  id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
  auction_id  BIGINT        NOT NULL COMMENT '竞价事件',
  lot_id      BIGINT        NOT NULL COMMENT '竞价批次',
  supplier_id BIGINT        NOT NULL COMMENT '中标供应商',
  bid_id      BIGINT        NOT NULL COMMENT '中标出价',
  amount      DECIMAL(19,4) NOT NULL COMMENT '成交单价',
  status      VARCHAR(16)   NOT NULL DEFAULT 'DRAFT'
              COMMENT 'DRAFT/APPROVED/PO_CREATED/CANCELED',
  approved_by BIGINT        NULL COMMENT '审批人',
  approved_at DATETIME(3)   NULL COMMENT '审批时间',
  version     INT           NOT NULL DEFAULT 0 COMMENT '乐观锁版本',
  created_by  BIGINT        NOT NULL DEFAULT 0 COMMENT '创建人',
  created_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
  updated_at  DATETIME(3)   NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
              ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
  PRIMARY KEY (id),
  UNIQUE KEY uk_auction_result_lot (auction_id, lot_id),
  KEY idx_auction_result_supplier (supplier_id, status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT = '竞价结果';

ALTER TABLE purchase_order
  ADD COLUMN source_auction_id BIGINT NULL COMMENT '来源竞价事件',
  ADD COLUMN auction_result_id BIGINT NULL COMMENT '来源竞价结果';
