package com.example.analyst;

import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 自动数据画像：在让 LLM 选统计方法之前，先告诉它这张表长什么样。
 * 没有这一步，LLM 就是蒙眼开枪——它不知道列是连续还是类别、有没有缺失、分布如何。
 */
@Component
public class DataProfiler {

    public String profile(DataTable table) {
        StringBuilder sb = new StringBuilder();
        sb.append("数据规模：").append(table.rowCount()).append(" 行，")
          .append(table.colCount()).append(" 列。\n");

        List<Integer> numeric = table.numericColumnIndexes();
        for (int c = 0; c < table.columns.size(); c++) {
            String col = table.columns.get(c);
            boolean isNum = numeric.contains(c);
            sb.append("\n列[").append(c).append("] ").append(col)
              .append(isNum ? "（数值型）" : "（文本/类别型）");

            int missing = 0;
            List<Object> sample = new ArrayList<>();
            for (int r = 0; r < table.rowCount(); r++) {
                Object v = table.get(r, c);
                if (v == null || (v instanceof String s && s.isBlank())) missing++;
                else if (sample.size() < 5) sample.add(v);
            }
            sb.append("，缺失 ").append(missing).append(" 个。");

            if (isNum) {
                DescriptiveStatistics stats = new DescriptiveStatistics();
                for (double d : table.columnAsDouble(c)) if (!Double.isNaN(d)) stats.addValue(d);
                if (stats.getN() > 0) {
                    sb.append(" 描述统计：min=").append(round(stats.getMin()))
                      .append(" max=").append(round(stats.getMax()))
                      .append(" mean=").append(round(stats.getMean()))
                      .append(" std=").append(round(stats.getStandardDeviation()));
                }
            } else {
                sb.append(" 样例：").append(sample);
            }
        }
        return sb.toString();
    }

    private double round(double d) {
        return Math.round(d * 100.0) / 100.0;
    }
}
