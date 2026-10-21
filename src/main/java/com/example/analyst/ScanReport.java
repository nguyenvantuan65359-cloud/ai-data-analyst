package com.example.analyst;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次体检的结果。**全是数字和判据，没有一句由模型生成的解释**——
 * 扫描与判定是计算，交给 Java；解读留给模型（另一步）。
 *
 * 为什么这么切：把几百个切片丢给模型逐个看，预算和上下文会一起烧光，
 * 而且它一定会开始编因果。计算归程序，解释归模型。
 */
public class ScanReport {

    /** "A" 时间模式（能判趋势/异常）/ "B" 横截面模式（不能判趋势） */
    public String mode;
    public String modeLabel;
    /** 报告第一行必须让读者知道结论强度：把横截面说成"上涨"是致命误导 */
    public String headline;

    public String timeCol;
    public int periodCount;
    public List<String> periods = new ArrayList<>();
    public List<Double> overallSeries = new ArrayList<>();
    public double growth;
    public double totalDelta;

    public String metric;
    public List<String> measures = new ArrayList<>();
    public List<String> dims = new ArrayList<>();
    public List<String> notes = new ArrayList<>();

    public List<Finding> findings = new ArrayList<>();
    public List<Finding> blocked = new ArrayList<>();
    public List<String> quality = new ArrayList<>();

    public int slicesScanned;
    /** 满足判据的候选总数（findings 只展示前 N 条） */
    public int totalCandidates;
    public long elapsedMs;

    public static class Finding {
        /** 持续下滑 / 持续增长 / 单期异常 / 效率偏高 / 效率偏低 */
        public String kind;
        public String slice;
        public String metric;
        public String dim;

        public double last;
        public double prev;
        public double delta;
        public double pct;
        /** 超额变化：切片变化 − 按大盘增速应有的变化。>0 说明跑赢大盘 */
        public double excess;
        /** 3 期对 3 期的超额变化（抓"连续三期慢慢掉"，单期看不出来） */
        public double excess3;
        public double z;
        /** 横截面模式专用：实际 ÷ 期望（O/E）。>1 表示同类中偏高，<1 偏低 */
        public double oe;
        /** 占整体变化的比例（分母取 |整体Δ|，否则整体下跌时正超额会算出负占比） */
        public double sharePct;
        public int n;

        /** 强 / 中 / 弱 —— 由判据命中条数决定，不是模型的感觉 */
        public String strength;
        /** 可信度系数 0.5~1：交叉切片每期样本不足时降权，避免它挤掉可靠的单维结论 */
        public double reliability = 1.0;
        /** 反向检验：同期同类切片是否同向变化 */
        public String peer;
        /** 可复现这条结论的 SQL */
        public String evidence;
        /** 判据说明，例如"3 期累计 −18.7%，稳健 z=−3.4" */
        public String why;
    }
}
