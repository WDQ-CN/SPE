-- E3 销售发货补币种（退货/成本事件需要）。
-- 只增不改：应用后本文件冻结（06 §1.3）。

ALTER TABLE sales_shipment
  ADD COLUMN currency CHAR(3) NOT NULL DEFAULT 'CNY' COMMENT '币种';
