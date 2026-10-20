import com.example.analyst.ArithCheck;

public class TestArith {
    static void t(String name, String text) {
        System.out.println("=== " + name + " ===");
        var issues = ArithCheck.check(text);
        if (issues.isEmpty()) System.out.println("  通过（无问题）");
        else issues.forEach(i -> System.out.println("  打回：" + i));
    }

    public static void main(String[] a) {
        t("A 错差值（应打回）",
          "华南比海外多18,002元（+10.9%）。华南185,057元，海外164,699元，海外是最低地区。");
        t("B 正确差值（应通过）",
          "华南比海外多20,358元（+12.4%）。华南185,057元，海外164,699元，海外是最低地区。");
        t("C 正确百分比（应通过）",
          "服饰140,849元最高，电子121,972元最低，最高比最低多18,877元（15.5%）。");
        t("D 分母写错（应打回）",
          "服饰140,849元最高，电子121,972元最低，最高比最低多18,877元（13.4%）。");
    }
}
