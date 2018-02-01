package com.linkedin.coral.hive.hive2rel;

import com.google.common.collect.ImmutableList;
import com.linkedin.coral.hive.hive2rel.functions.UnknownSqlFunctionException;
import java.io.IOException;
import org.apache.calcite.plan.RelOptUtil;
import org.apache.calcite.rel.RelNode;
import org.apache.calcite.rel.rel2sql.RelToSqlConverter;
import org.apache.calcite.sql.SqlDialect;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.type.SqlTypeName;
import org.apache.calcite.tools.RelBuilder;
import org.apache.hadoop.hive.metastore.api.MetaException;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.thrift.TException;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import static com.linkedin.coral.hive.hive2rel.ToRelConverter.*;
import static org.testng.Assert.*;


public class HiveToRelConverterTest {

  @BeforeClass
  public static void beforeClass() throws IOException, HiveException, MetaException {
    ToRelConverter.setup();
  }

  @Test
  public void testBasic() {
    String sql = "SELECT * from foo";
    RelNode rel = converter.convertSql(sql);
    RelBuilder relBuilder = createRelBuilder();
    RelNode expected = relBuilder.scan(ImmutableList.of("hive", "default", "foo"))
        .project(ImmutableList.of(relBuilder.field("a"),
            relBuilder.field("b"), relBuilder.field("c")),
            ImmutableList.of(), true)
        .build();
    verifyRel(rel, expected);
  }

  @Test
  public void testIFUDF() {
    {
      final String sql = "SELECT if( a > 10, null, 15) FROM foo";
      String expected = "LogicalProject(EXPR$0=[if(>($0, 10), null, 15)])\n" +
          "  LogicalTableScan(table=[[hive, default, foo]])\n";
      RelNode rel = converter.convertSql(sql);
      assertEquals(RelOptUtil.toString(rel), expected);
      assertEquals(rel.getRowType().getFieldCount(), 1);
      assertEquals(rel.getRowType().getFieldList().get(0).getType().getSqlTypeName(), SqlTypeName.INTEGER);
    }
    {
      final String sql = "SELECT if(a > 10, b, 'abc') FROM foo";
      String expected = "LogicalProject(EXPR$0=[if(>($0, 10), $1, 'abc')])\n" +
          "  LogicalTableScan(table=[[hive, default, foo]])\n";
      assertEquals(relToString(sql), expected);
    }
    {
      final String sql = "SELECT if(a > 10, null, null) FROM foo";
      String expected = "LogicalProject(EXPR$0=[if(>($0, 10), null, null)])\n" +
          "  LogicalTableScan(table=[[hive, default, foo]])\n";
      assertEquals(relToString(sql), expected);
    }
  }

  @Test
  public void testRegexpExtractUDF() {
    {
      final String sql = "select regexp_extract(b, 'a(.*)$', 1) FROM foo";
      String expected = "LogicalProject(EXPR$0=[regexp_extract($1, 'a(.*)$', 1)])\n" +
          "  LogicalTableScan(table=[[hive, default, foo]])\n";
      RelNode rel = converter.convertSql(sql);
      assertEquals(RelOptUtil.toString(rel), expected);
      assertTrue(rel.getRowType().isStruct());
      assertEquals(rel.getRowType().getFieldCount(), 1);
      assertEquals(rel.getRowType().getFieldList().get(0).getType().getSqlTypeName(), SqlTypeName.VARCHAR);
    }
  }

  @Test
  public void testDaliUDFCall() {
    // TestUtils sets up this view with proper function parameters matching dali setup
    RelNode rel = converter.convertView("test", "tableOneView");
    String expectedPlan = "LogicalProject(EXPR$0=[com.linkedin.coral.hive.hive2rel.CoralTestUDF($0)])\n" +
        "  LogicalTableScan(table=[[hive, test, tableone]])\n";
    assertEquals(RelOptUtil.toString(rel), expectedPlan);
  }

  @Test (expectedExceptions = UnknownSqlFunctionException.class)
  public void testUnresolvedUdfError() {
    final String sql = "SELECT default_foo_IsTestMemberId(a) from foo";
    RelNode rel = converter.convertSql(sql);
  }

  @Test
  public void testViewExpansion() throws TException {
    {
      String sql = "SELECT avg(sum_c) from foo_view";
      RelNode rel = converter.convertSql(sql);
      // we don't do rel to rel comparison for this method because of casting operation and expression naming rules
      // it's easier to compare strings
      String expectedPlan = "LogicalAggregate(group=[{}], EXPR$0=[AVG($0)])\n" +
          "  LogicalProject(sum_c=[$1])\n" +
          "    LogicalProject(bcol=[$0], sum_c=[CAST($1):DOUBLE])\n" +
          "      LogicalAggregate(group=[{0}], sum_c=[SUM($1)])\n" +
          "        LogicalProject(bcol=[$1], c=[$2])\n" +
          "          LogicalTableScan(table=[[hive, default, foo]])\n";
      assertEquals(RelOptUtil.toString(rel), expectedPlan);
    }
  }

  @Test
  public void testArrayType() {
    final String sql = "SELECT array(1,2,3)";
    final String expected = "LogicalProject(EXPR$0=[ARRAY(1, 2, 3)])\n" +
        "  LogicalValues(tuples=[[{ 0 }]])\n";
    assertEquals(RelOptUtil.toString(converter.convertSql(sql)), expected);
  }

  @Test
  public void testMapType() {
    final String sql = "SELECT map('abc', 123, 'def', 567)";
    String generated = relToString(sql);
    final String expected =
        "LogicalProject(EXPR$0=[MAP('abc', 123, 'def', 567)])\n" + "  LogicalValues(tuples=[[{ 0 }]])\n";
    assertEquals(generated, expected);
  }

  @Test
  public void testStructType() {
    final String sql = "SELECT struct(10, 15, 20.23)";
    String generated = relToString(sql);
    final String expected = "LogicalProject(EXPR$0=[ROW(10, 15, 20.23)])\n" +
        "  LogicalValues(tuples=[[{ 0 }]])\n";
    assertEquals(generated, expected);
  }

  @Test
  public void testNamedStruct() {
    final String sql = "SELECT named_struct('abc', cast(NULL as int), 'def', 150)";
    String generated = relToString(sql);
    System.out.println(generated);
    System.out.println(new RelToSqlConverter(SqlDialect.DatabaseProduct.POSTGRESQL.getDialect()).visitChild(0, converter.convertSql(sql)).asStatement());
  }

  // TODO: move lateral tests to separate class. We've already done the necessary refactoring
  // that's pending on RB approval.
  @Test
  public void testLateralView() {
    final String sql = "SELECT a, ccol from complex lateral view explode(complex.c) t as ccol";
    HiveToRelConverter rel2sql = HiveToRelConverter.create(new HiveMscAdapter(ToRelConverter.getMsc()));
    RelNode relNode = rel2sql.convertSql(sql);
    String expected = "LogicalProject(a=[$0], ccol=[$5])\n" +
        "  LogicalCorrelate(correlation=[$cor0], joinType=[inner], requiredColumns=[{2}])\n" +
        "    LogicalTableScan(table=[[hive, default, complex]])\n" +
        "    LogicalProject(EXPR$0=[$0])\n" +
        "      Uncollect\n" +
        "        LogicalProject(c=[$cor0.c])\n" +
        "          LogicalValues(tuples=[[{ 0 }]])\n";
    assertEquals(RelOptUtil.toString(relNode), expected);
  }

  @Test
  public void testLateralViewOuter() {
    final String sql = "SELECT a, t.ccol from complex lateral view outer explode(complex.c) t as ccol";
    HiveToRelConverter rel2sql = HiveToRelConverter.create(new HiveMscAdapter(ToRelConverter.getMsc()));
    RelNode relNode = rel2sql.convertSql(sql);
    String expected = "LogicalProject(a=[$0], ccol=[$5])\n" +
        "  LogicalCorrelate(correlation=[$cor0], joinType=[inner], requiredColumns=[{2}])\n" +
        "    LogicalTableScan(table=[[hive, default, complex]])\n" +
        "    LogicalProject(EXPR$0=[$0])\n" +
        "      Uncollect\n" +
        "        LogicalProject(EXPR$0=[if(AND(IS NOT NULL($cor0.c), >(CARDINALITY($cor0.c), 0)), $cor0.c, ARRAY(null))])\n"+
        "          LogicalValues(tuples=[[{ 0 }]])\n";

    assertEquals(RelOptUtil.toString(relNode), expected);
  }

  @Test
  public void testCalciteLateral() throws SqlParseException {
    final String sql = "SELECT a, t.c from complex, lateral (select * from unnest(complex.c) ) as t(c)";
    SqlParser parser = SqlParser.create(sql, SqlParser.Config.DEFAULT);
    SqlNode sqlNode = parser.parseQuery();
    HiveToRelConverter hive2Rel = HiveToRelConverter.create(new HiveMscAdapter(ToRelConverter.getMsc()));
    RelNode relNode = hive2Rel.toRel(sqlNode);
    System.out.println(RelOptUtil.toString(relNode));
  }

  @Test
  public void testMultipleMixedLateralClauses() {
    final String sql = "SELECT a, ccol, r.anotherCCol from complex " +
        " lateral view outer explode(complex.c) t as ccol " +
        " lateral view explode(complex.c) r as anotherCCol";
    HiveToRelConverter rel2sql = HiveToRelConverter.create(new HiveMscAdapter(ToRelConverter.getMsc()));
    RelNode relNode = rel2sql.convertSql(sql);
    String expected = "LogicalProject(a=[$0], ccol=[$5], anotherCCol=[$6])\n" +
        "  LogicalCorrelate(correlation=[$cor3], joinType=[inner], requiredColumns=[{2}])\n" +
        "    LogicalCorrelate(correlation=[$cor0], joinType=[inner], requiredColumns=[{2}])\n" +
        "      LogicalTableScan(table=[[hive, default, complex]])\n" +
        "      LogicalProject(EXPR$0=[$0])\n" +
        "        Uncollect\n" +
        "          LogicalProject(EXPR$0=[if(AND(IS NOT NULL($cor0.c), >(CARDINALITY($cor0.c), 0)), $cor0.c, ARRAY(null))])\n" +
        "            LogicalValues(tuples=[[{ 0 }]])\n" +
        "    LogicalProject(EXPR$0=[$0])\n" +
        "      Uncollect\n" +
        "        LogicalProject(c=[$cor3.c])\n" +
        "          LogicalValues(tuples=[[{ 0 }]])\n";
    assertEquals(RelOptUtil.toString(relNode), expected);
  }

  // TODO: Enable me. This is dependent on another RB being approved. And this may fail
  // Such usages are rare so pushing this to make progress
  @Test(enabled = false)
  public void testComplexLateralExplodeOperand() {
    final String sql = "SELECT a, ccol from complex lateral view " +
        " explode(if(size(complex.c) > 5, array(10.5), complex.c)) t as ccol";
    System.out.println(relToString(sql));
  }

  private String relToString(String sql) {
    return RelOptUtil.toString(converter.convertSql(sql));
  }
}