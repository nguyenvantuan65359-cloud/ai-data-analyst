package com.example.analyst;

import java.util.ArrayList;
import java.util.List;

/**
 * 内存里的二维表，作为文件导入 / SQL 查询结果在系统内部的统一表示。
 * 接入层产出的任何数据都收敛成这个结构，下游画像、统计、LLM 都只看它。
 */
public class DataTable {

    public final List<String> columns;
    public final List<List<Object>> rows;

    public DataTable(List<String> columns, List<List<Object>> rows) {
        this.columns = columns;
        this.rows = rows;
    }

    public int rowCount() {
        return rows.size();
    }

    public int colCount() {
        return columns.size();
    }

    /** 取某一列、按行号取值 */
    public Object get(int row, int col) {
        return rows.get(row).get(col);
    }

    /** 取某一列，尽量转成 double 数组（非数值返回 NaN），供统计工具使用 */
    public double[] columnAsDouble(int col) {
        double[] out = new double[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            Object v = rows.get(i).get(col);
            if (v instanceof Number n) {
                out[i] = n.doubleValue();
            } else if (v instanceof String s) {
                try {
                    out[i] = Double.parseDouble(s.trim());
                } catch (Exception e) {
                    out[i] = Double.NaN;
                }
            } else {
                out[i] = Double.NaN;
            }
        }
        return out;
    }

    /** 只保留数值列（用于自动画像时判断列类型） */
    public List<Integer> numericColumnIndexes() {
        List<Integer> idx = new ArrayList<>();
        if (rows.isEmpty()) return idx;
        for (int c = 0; c < columns.size(); c++) {
            boolean numeric = true;
            int sample = Math.min(rows.size(), 20);
            for (int r = 0; r < sample; r++) {
                Object v = rows.get(r).get(c);
                if (v instanceof Number) continue;
                if (v instanceof String s) {
                    try {
                        Double.parseDouble(s.trim());
                    } catch (Exception e) {
                        numeric = false;
                        break;
                    }
                } else if (v != null) {
                    numeric = false;
                    break;
                }
            }
            if (numeric) idx.add(c);
        }
        return idx;
    }
}
