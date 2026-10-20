-- 示例数据表：销售明细（用于演示 AI 查库 / 统计分析）
-- 兼容 H2(MODE=MySQL) 与 MySQL/TiDB
CREATE TABLE IF NOT EXISTS sales (
    id            INT PRIMARY KEY,
    sale_month    INT,
    region        VARCHAR(20),
    category      VARCHAR(20),
    sales_amount  DOUBLE,
    orders        INT,
    return_qty    INT
);
