import java.sql.*; import java.util.*;
/** 复刻 ExternalDb.connect() 完全相同的内省逻辑(getTables null,null)，验证多个 schema 场景会不会爆 */
public class Probe3 {
  public static void main(String[] a){
    List<String[]> cases=List.of(
      new String[]{"Rfam 指定库","jdbc:mysql://mysql-rfam-public.ebi.ac.uk:4497/Rfam","rfamro",""},
      new String[]{"Ensembl 指定人库","jdbc:mysql://ensembldb.ensembl.org:3306/homo_sapiens_core_99_38","anonymous",""},
      new String[]{"Ensembl 不指定库","jdbc:mysql://ensembldb.ensembl.org:3306/","anonymous",""},
      new String[]{"UCSC 指定 visiGene","jdbc:mysql://genome-mysql.soe.ucsc.edu:3306/visiGene","genome",""}
    );
    for(String[] c:cases){
      System.out.println("---- "+c[0]+"  ("+c[1]+")");
      try(Connection conn=DriverManager.getConnection(c[1],c[2],c[3])){
        DatabaseMetaData md=conn.getMetaData();
        System.out.println("   connectionCatalog="+conn.getCatalog());
        List<String> ts=new ArrayList<>(); Set<String> schemSet=new LinkedHashSet<>();
        long t0=System.currentTimeMillis();
        try(var rs=md.getTables(null,null,"%",new String[]{"TABLE"})){
          while(rs.next()&&ts.size()<100){
            String s=rs.getString("TABLE_SCHEM");
            if(s!=null&&s.equalsIgnoreCase("INFORMATION_SCHEMA")) continue;
            ts.add(rs.getString("TABLE_NAME")); schemSet.add(s);
          }
        }
        long cost=System.currentTimeMillis()-t0;
        System.out.println("   表数="+ts.size()+"  涉及 schema 数="+schemSet.size()+"  耗时="+cost+"ms");
        System.out.println("   schema 样本="+new ArrayList<>(schemSet).subList(0,Math.min(4,schemSet.size())));
        if(!ts.isEmpty()) System.out.println("   前6张表="+ts.subList(0,Math.min(6,ts.size())));
        // 试查：不加库前缀能不能查到（决定 AI 生成的 SQL 是否要带 schema 限定）
        if(!ts.isEmpty()){
          String t=ts.get(0); try(Statement st=conn.createStatement();
            ResultSet rs=st.executeQuery("SELECT COUNT(*) FROM `"+t+"`")){
            rs.next(); System.out.println("   无前缀查 "+t+" = "+rs.getLong(1)+" 行"); }
          catch(Exception e){ System.out.println("   无前缀查询失败(需带库名): "+e.getMessage()); }
        }
      }catch(Exception e){ System.out.println("   连接失败: "+e.getMessage()); }
    }
  }
}
