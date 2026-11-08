-- 深审补索引：只加索引，不改数据与约束。
-- 依据：EXPLAIN 显示以下查询走全表/临时表（2026-11-08 工作区审计）。
-- 只增不改：应用后本文件冻结（06 §1.3）。

-- 效期批次扫描：按 status + expiry_date 过滤并排序。
ALTER TABLE inventory_lot
  ADD KEY idx_lot_expiry (status, expiry_date, id);

-- 检验单详情/开 NCR 回查、CAPA 按 NCR 聚合。
ALTER TABLE ncr
  ADD KEY idx_ncr_inspection (inspection_id);
ALTER TABLE capa
  ADD KEY idx_capa_ncr (ncr_id);

-- 未收发票账龄：company + status + due_date。
ALTER TABLE sales_invoice
  ADD KEY idx_sales_invoice_open (company_id, status, due_date, id);

-- 销售退货/发货列表：company 开头的分页。
ALTER TABLE sales_return
  ADD KEY idx_sales_return_company (company_id, customer_id, status, id);
ALTER TABLE sales_shipment
  ADD KEY idx_sales_shipment_company (company_id, status, id);

-- 报表运行历史按公司分页。
ALTER TABLE report_run
  ADD KEY idx_report_run_company (company_id, id);

-- PO 取消时按 po_id 回滚合同消耗。
ALTER TABLE contract_consumption
  ADD KEY idx_consumption_po (po_id);

-- 工作流：同一人已审节点的 SoD 回查。
ALTER TABLE workflow_task
  ADD KEY idx_workflow_task_actor (instance_id, acted_by, status);

-- BOM：列表按公司分页；唯一版本按公司隔离（原唯一键缺 company_id，第二家公司同物料同版本会误撞）。
ALTER TABLE bom_header
  DROP INDEX uk_bom_item_version,
  ADD UNIQUE KEY uk_bom_company_item_version (company_id, item_id, version),
  ADD KEY idx_bom_company_id (company_id, id);
