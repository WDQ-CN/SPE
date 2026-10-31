-- 03 §51 P3-15：api_token 的查询只有按 id / token_hash，没有 status 前导的访问路径；
-- idx_api_token_status 是死索引（只增加写成本）。表规模小但保持索引清单真实。
ALTER TABLE api_token DROP INDEX idx_api_token_status;
