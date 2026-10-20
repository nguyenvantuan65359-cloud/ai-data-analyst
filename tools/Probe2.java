import java.sql.*;
import java.util.*;

/** 第二轮：指定具体小库，看单库表数量是否可用；并试对的用户名。 */
public class Probe2 {
  public static void main(String[] a) throws Exception {
    // 1) Rfam 官方只读账号应为 rfamro
    tryUser("jdbc:mysql://mysql-rfam-public.ebi.ac.uk:4497/Rfam", "rfamro", "");

    // 2) Ensembl：先看版本库名
    System.out.println("\n---- Ensembl schema 名探测（找人类最新 core 库 + 一个小 *_cdna 库）----");
    try (Connection c = DriverManager.getConnection("jdbc:mysql://ensembldb.ensembl.org:3306/","anonymous","")) {
      TreeSet<String> human=new TreeSet<>(); List<String> small=new ArrayList<>();
      try (ResultSet rs=c.getMetaData().getCatalogs()) {
        while(rs.next()){ String s=rs.getString(1);
          if(s.startsWith("homo_sapiens_core_")) human.add(s);
          if(s.startsWith("saccharomyces_cerevisiae_core_")) small.add(s); }
      }
      System.out.println("  human core 版本数: "+human.size()+"  最新: "+(human.isEmpty()?"-":human.last()));
      System.out.println("  酵母 core 库数: "+small.size()+"  最新可用: "+(small.isEmpty()?"-":small.get(small.size()-1)));
      for (String cand : List.of(human.isEmpty()?null:human.last(), small.isEmpty()?null:small.get(small.size()-1))) {
        if (cand==null) continue;
        countTables(c, cand);
      }
    }
    // 3) UCSC：找一个小而语义清晰的库
    System.out.println("\n---- UCSC 候选小库 ----");
    try (Connection c = DriverManager.getConnection("jdbc:mysql://genome-mysql.soe.ucsc.edu:3306/","genome","")) {
      for (String s : List.of("uniProt","hgFixed","proteins","visiGene")) countTables(c, s);
    }
  }
  static void tryUser(String url,String u,String p){
    System.out.println("["+u+"] "+url);
    try(Connection c=DriverManager.getConnection(url,u,p)){
      System.out.println("  OK 版本 "+c.getMetaData().getDatabaseProductVersion());
      countTables(c,"Rfam");
    }catch(Exception e){ System.out.println("  FAIL "+e.getMessage()); }
  }
  static void countTables(Connection c,String schema) throws Exception {
    List<String> t=new ArrayList<>(); List<Long> rows=new ArrayList<>();
    long t0=System.currentTimeMillis();
    try(ResultSet rs=c.getMetaData().getTables(schema,null,"%",new String[]{"TABLE","VIEW"})){
      while(rs.next()&&t.size()<500) t.add(rs.getString("TABLE_NAME")); }
    long cost=System.currentTimeMillis()-t0;
    System.out.println("  库 ["+schema+"] 表数="+t.size()+(t.size()==500?"(>=500截断)":"")+" 内省"+cost+"ms");
    if(!t.isEmpty()){ System.out.println("    前8张: "+String.join(", ",t.subList(0,Math.min(8,t.size()))));
      try(Statement st=c.createStatement();ResultSet rs=st.executeQuery("SELECT COUNT(*) FROM `"+schema+"`.`"+t.get(0)+"`")){
        rs.next(); System.out.println("    "+t.get(0)+" 行数="+rs.getLong(1)); }
      catch(Exception e){ System.out.println("    抽样失败 "+e.getMessage()); } }
  }
}
