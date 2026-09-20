import java.sql.*;
import java.util.*;

public final class SeedAndTransfer {
  public static void main(String[] args) throws Exception {
    String ou=System.getenv("AKIS_ORACLE_SOURCE_URL"), user=System.getenv("AKIS_ORACLE_SOURCE_USERNAME"), pw=System.getenv("AKIS_ORACLE_SOURCE_PASSWORD");
    String pu=System.getenv("AKIS_DB_URL"), puser=System.getenv("AKIS_DB_USERNAME"), ppw=System.getenv("AKIS_DB_PASSWORD");
    if (ou==null||user==null||pw==null||pu==null||puser==null||ppw==null) throw new IllegalStateException("fixture credentials missing");
    String table="AKIS_MILLION_TEST";
    try (Connection o=DriverManager.getConnection(ou,user,pw); Connection p=DriverManager.getConnection(pu,puser,ppw)) {
      o.setAutoCommit(false); try (Statement s=o.createStatement()) { try { s.executeUpdate("drop table INNOVA_ODI."+table+" purge"); } catch(SQLException ignored) {} s.executeUpdate("create table INNOVA_ODI."+table+" (ID number(12) primary key, PAYLOAD varchar2(80) not null, CREATED_AT timestamp not null)"); }
      try (PreparedStatement ins=o.prepareStatement("insert into INNOVA_ODI."+table+" (ID,PAYLOAD,CREATED_AT) values (?,?,systimestamp)")) { for(int i=1;i<=1_000_000;i++){ ins.setInt(1,i); ins.setString(2,"AKIS_TEST_"+i); ins.addBatch(); if(i%5000==0) ins.executeBatch(); } ins.executeBatch(); }
      o.commit();
      try (Statement s=p.createStatement()) { try { s.executeUpdate("drop table if exists akis_pg_target.\"AKIS_MILLION_TEST\""); } catch(SQLException ignored) {} s.executeUpdate("create table akis_pg_target.\"AKIS_MILLION_TEST\" (\"ID\" bigint primary key, \"PAYLOAD\" varchar(80) not null, \"CREATED_AT\" timestamp not null)"); }
      p.setAutoCommit(false); try (PreparedStatement ins=p.prepareStatement("insert into akis_pg_target.\"AKIS_MILLION_TEST\" (\"ID\",\"PAYLOAD\",\"CREATED_AT\") values (?,?,?)"); Statement q=o.createStatement()) {
        q.setFetchSize(5000); try(ResultSet r=q.executeQuery("select ID,PAYLOAD,CREATED_AT from INNOVA_ODI."+table+" order by ID")){ int n=0; while(r.next()){ ins.setLong(1,r.getLong(1)); ins.setString(2,r.getString(2)); ins.setTimestamp(3,r.getTimestamp(3)); ins.addBatch(); if(++n%5000==0) ins.executeBatch(); } ins.executeBatch(); }
      } p.commit();
      try(Statement s=p.createStatement(); ResultSet r=s.executeQuery("select count(*) from akis_pg_target.\"AKIS_MILLION_TEST\"")){r.next(); System.out.println("TRANSFERRED_ROWS="+r.getLong(1));}
    }
  }
}
