import com.example.analyst.GroundingCheck;

public class TestGround {
    static void t(String name, String answer, String trace) {
        System.out.println("=== " + name + " ===");
        var issues = GroundingCheck.check(answer, trace);
        if (issues.isEmpty()) System.out.println("  通过（无问题）");
        else issues.forEach(i -> System.out.println("  提示：" + i));
    }

    public static void main(String[] a) {
        String trace = "[anova] 单因素 ANOVA CATEGORY ~ SALES_AMOUNT：F=0.490 p=0.6907，"
                + "组均值 服饰 7824.94 收纳 7261.50；[图表数据] 各品类合计 140849 130707 121972 164699 185057 167055";

        t("小数被截断（不应误报）",
                "p ≈ 0.691，远大于 0.05；步长 0.001 得 0.694。", trace);
        t("派生差值（不应误报）",
                "头尾差额 18,877 元，占 516,811 的 3.65%。", trace);
        t("凭空数字（应提示）",
                "该品类带来额外 555,555 元的增量空间。", trace);
    }
}
