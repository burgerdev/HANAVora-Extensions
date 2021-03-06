package org.apache.spark.sql

import com.sap.spark.PlanTest
import org.apache.spark.Logging
import org.apache.spark.sql.catalyst.analysis._
import org.apache.spark.sql.catalyst.expressions._
import org.apache.spark.sql.catalyst.expressions.tablefunctions.UnresolvedTableFunction
import org.apache.spark.sql.catalyst.plans.logical._
import org.apache.spark.sql.catalyst.{SimpleCatalystConf, TableIdentifier}
import org.apache.spark.sql.execution.datasources.CreateNonPersistentViewCommand
import org.apache.spark.sql.sources.sql.{Dimension, Plain}
import org.apache.spark.sql.types._
import org.apache.spark.util.AnnotationParsingUtils
import org.scalatest.FunSuite

import scala.reflect.ClassTag
import scala.reflect.runtime.universe.TypeTag

class SapSqlParserSuite
  extends FunSuite
  with PlanTest
  with AnnotationParsingUtils
  with Logging {

  def t1: LogicalPlan = new LocalRelation(output = Seq(
    new AttributeReference("pred", StringType, nullable = true, metadata = Metadata.empty)(),
    new AttributeReference("succ", StringType, nullable = false, metadata = Metadata.empty)(),
    new AttributeReference("ord", StringType, nullable = false, metadata = Metadata.empty)()
  ).map(_.toAttribute)
  )

  def catalog: Catalog = {
    val catalog = new SimpleCatalog(SimpleCatalystConf(true))
    catalog.registerTable(TableIdentifier("T1"), t1)
    catalog
  }

  def analyzer: Analyzer = new Analyzer(catalog, EmptyFunctionRegistry, SimpleCatalystConf(true))
  test("basic case") {
    val parser = new SapParserDialect
    val result = parser.parse(
      """
        |SELECT 1 FROM HIERARCHY (
        | USING T1 AS v
        | JOIN PRIOR u ON v.pred = u.succ
        | ORDER SIBLINGS BY ord
        | START WHERE pred IS NULL
        | SET Node
        |) AS H
      """.stripMargin)

    val expected = Project(AliasUnresolver(Literal(1)), Subquery("H", Hierarchy(
      AdjacencyListHierarchySpec(
        source = UnresolvedRelation(TableIdentifier("T1"), Some("v")),
        parenthoodExp = EqualTo(UnresolvedAttribute("v.pred"), UnresolvedAttribute("u.succ")),
        childAlias = "u",
        startWhere = Some(IsNull(UnresolvedAttribute("pred"))),
        orderBy = SortOrder(UnresolvedAttribute("ord"), Ascending) :: Nil),
      node = UnresolvedAttribute("Node")
    )))
    comparePlans(expected, result)

    val analyzed = analyzer.execute(result)
    log.info(s"$analyzed")
  }

  // variables for raw select tests
  val rawSqlString = "SELECT something bla FROM A"
  val className = "class.name"

  test ("RAW SQL: ``select ....`` USING class.name") {
    // ('SQL COMMANDO FROM A' USING com.sap.spark.engines) JOIN SELECT * FROM X
    assert(SapSqlParser.parse(s"``$rawSqlString`` USING $className")
      .equals(UnresolvedSelectUsing(rawSqlString, className)))
  }

  test ("RAW SQL: ``select ....`` USING class.name AS (schema)") {
    val schema = "(a integer, b double)"
    val schemaFields = Seq(StructField("a", IntegerType), StructField("b", DoubleType))

    // ('SQL COMMANDO FROM A' USING com.sap.spark.engines) JOIN SELECT * FROM X
    assert(SapSqlParser.parse(s"``$rawSqlString`` USING $className AS $schema")
      .equals(UnresolvedSelectUsing(rawSqlString, className, Some(schemaFields))))
  }

  test ("RAW SQL: ``select ....`` USING class.name AS () - empty schema") {
    // ('SQL COMMANDO FROM A' USING com.sap.spark.engines) JOIN SELECT * FROM X
    assert(SapSqlParser.parse(s"``$rawSqlString`` USING $className AS ()")
      .equals(UnresolvedSelectUsing(rawSqlString, className, Some(Seq.empty))))
  }

  test("parse system table") {
    val parsed = SapSqlParser.parse("SELECT * FROM SYS.TABLES USING com.sap.spark")

    assertResult(Project(                               // SELECT
      Seq(UnresolvedAlias(UnresolvedStar(None))),       // *
      UnresolvedProviderBoundSystemTable("TABLES",      // FROM SYS.TABLES
        "com.sap.spark", Map.empty)                     // USING com.sap.spark
    ))(parsed)
  }

  test("parse system table with SYS_ prefix (VORASPARK-277)") {
    val statements =
      "SELECT * FROM SYS_TABLES USING com.sap.spark" ::
      "SELECT * FROM sys_TABLES USING com.sap.spark" ::
      "SELECT * FROM SyS_TABLES USING com.sap.spark" :: Nil

    val parsedStatements = statements.map(SapSqlParser.parse)

    parsedStatements.foreach { parsed =>
      assertResult(Project(                               // SELECT
        Seq(UnresolvedAlias(UnresolvedStar(None))),       // *
        UnresolvedProviderBoundSystemTable("TABLES",      // FROM SYS.TABLES
          "com.sap.spark", Map.empty)                     // USING com.sap.spark
      ))(parsed)
    }
  }

  test("parse system table with options") {
    val parsed = SapSqlParser.parse("SELECT * FROM SYS.TABLES " +
      "USING com.sap.spark OPTIONS (foo \"bar\")")

    assertResult(Project(                               // SELECT
      Seq(UnresolvedAlias(UnresolvedStar(None))),       // *
      UnresolvedProviderBoundSystemTable("TABLES",      // FROM SYS.TABLES
        "com.sap.spark", Map("foo" -> "bar"))     // USING com.sap.spark
    ))(parsed)
  }

  test("parse table function") {
    val parsed = SapSqlParser.parse("SELECT * FROM describe_table(SELECT * FROM persons)")

    assert(parsed == Project(                           // SELECT
      Seq(UnresolvedAlias(UnresolvedStar(None))),       // *
      UnresolvedTableFunction("describe_table",         // FROM describe_table(
        Seq(Project(                                    // SELECT
          Seq(UnresolvedAlias(UnresolvedStar(None))),   // *
          UnresolvedRelation(TableIdentifier("persons"))            // FROM persons
        )))))                                           // )
  }

  test("fail on incorrect table function") {
    // This should fail since a projection statement is required
    intercept[SapParserException] {
      SapSqlParser.parse("SELECT * FROM describe_table(persons)")
    }
  }

  test("'order siblings by' with multiple expressions") {
    val parser = new SapParserDialect
    val result = parser.parse(
      """
        |SELECT 1 FROM HIERARCHY (
        | USING T1 AS v
        | JOIN PRIOR u ON v.pred = u.succ
        | ORDER SIBLINGS BY myAttr ASC, otherAttr DESC, yetAnotherAttr
        | START WHERE pred IS NULL
        | SET Node
        |) AS H
      """.stripMargin)
    val expected = Project(AliasUnresolver(Literal(1)), Subquery("H", Hierarchy(
      AdjacencyListHierarchySpec(
        source = UnresolvedRelation(TableIdentifier("T1"), Some("v")),
        parenthoodExp = EqualTo(UnresolvedAttribute("v.pred"), UnresolvedAttribute("u.succ")),
        childAlias = "u",
        startWhere = Some(IsNull(UnresolvedAttribute("pred"))),
        orderBy = SortOrder(UnresolvedAttribute("myAttr"), Ascending) ::
          SortOrder(UnresolvedAttribute("otherAttr"), Descending) ::
          SortOrder(UnresolvedAttribute("yetAnotherAttr"), Ascending) :: Nil
      ),
      node = UnresolvedAttribute("Node")
    )))
    comparePlans(expected, result)

    val analyzed = analyzer.execute(result)
    log.info(s"$analyzed")
  }

    test("no 'order siblings by' clause") {
      val parser = new SapParserDialect
      val result = parser.parse(
        """
          |SELECT 1 FROM HIERARCHY (
          | USING T1 AS v
          | JOIN PRIOR u ON v.pred = u.succ
          | START WHERE pred IS NULL
          | SET Node
          |) AS H
        """.stripMargin)
      val expected = Project(AliasUnresolver(Literal(1)), Subquery("H", Hierarchy(
        AdjacencyListHierarchySpec(
          source = UnresolvedRelation(TableIdentifier("T1"), Some("v")),
          parenthoodExp = EqualTo(UnresolvedAttribute("v.pred"), UnresolvedAttribute("u.succ")),
          childAlias = "u",
          startWhere = Some(IsNull(UnresolvedAttribute("pred"))),
          orderBy = Nil
        ), node = UnresolvedAttribute("Node")
      )))
      comparePlans(expected, result)

      val analyzed = analyzer.execute(result)
      log.info(s"$analyzed")
  }

  test("create temporary view") {
    val parser = new SapParserDialect
    val result = parser.parse("CREATE TEMPORARY VIEW myview AS SELECT 1 FROM mytable")
    val expected = CreateNonPersistentViewCommand(
      Plain, TableIdentifier("myview"),
      Project(AliasUnresolver(Literal(1)), UnresolvedRelation(TableIdentifier("mytable"))),
      temporary = true)
    comparePlans(expected, result)
  }

  test("create view") {
    val parser = new SapParserDialect
    val result = parser.parse("CREATE VIEW myview AS SELECT 1 FROM mytable")
    val expected = CreateNonPersistentViewCommand(
      Plain, TableIdentifier("myview"),
      Project(AliasUnresolver(Literal(1)), UnresolvedRelation(TableIdentifier("mytable"))),
      temporary = false)
    comparePlans(expected, result)
  }

  test("create temporary view of a hierarchy") {
    val parser = new SapParserDialect
    val result = parser.parse("""
                              CREATE TEMPORARY VIEW HV AS SELECT 1 FROM HIERARCHY (
                                 USING T1 AS v
                                 JOIN PRIOR u ON v.pred = u.succ
                                 START WHERE pred IS NULL
                                 SET Node
                                ) AS H
                              """.stripMargin)
    val expected = CreateNonPersistentViewCommand(
      Plain, TableIdentifier("HV"),
      Project(AliasUnresolver(Literal(1)), Subquery("H", Hierarchy(
        AdjacencyListHierarchySpec(source = UnresolvedRelation(TableIdentifier("T1"), Some("v")),
          parenthoodExp = EqualTo(UnresolvedAttribute("v.pred"), UnresolvedAttribute("u.succ")),
          childAlias = "u",
          startWhere = Some(IsNull(UnresolvedAttribute("pred"))),
          orderBy = Nil),
        node = UnresolvedAttribute("Node")
      ))), temporary = true)
    comparePlans(expected, result)
  }


  // scalastyle:off magic.number
  test("create view with annotations") {
    val parser = new SapParserDialect

    val result = parser.parse("CREATE VIEW aview AS SELECT " +
      "A AS AL @ (foo = 'bar')," +
      "B AS BL @ (foo = 'bar', baz = 'bla')," +
      "C AS CL @ (foo = ('bar', 'baz'))," +
      "D AS DL @ (foo = '?')," +
      "E AS EL @ (* = 'something')," +
      "F AS FL @ (foo = null)," +
      "G AS GL @ (foo = 123)," +
      "H AS HL @ (foo = 1.23) " +
      "FROM atable")

    assert(result.isInstanceOf[CreateNonPersistentViewCommand])

    val createView = result.asInstanceOf[CreateNonPersistentViewCommand]

    assertResult(createView.identifier.table)("aview")

    assert(createView.kind == Plain)

    assert(createView.plan.isInstanceOf[Project])

    val projection = createView.plan.asInstanceOf[Project]

    assertResult(UnresolvedRelation(TableIdentifier("atable")))(projection.child)

    val actual = projection.projectList

    val expected = Seq(
      ("AL", UnresolvedAttribute("A"), Map("foo" -> Literal.create("bar", StringType))),
      ("BL", UnresolvedAttribute("B"), Map("foo" ->
        Literal.create("bar", StringType), "baz" -> Literal.create("bla", StringType))),
      ("CL", UnresolvedAttribute("C"),
        Map("foo" -> Literal.create("""[bar,baz]""", StringType))),
      ("DL", UnresolvedAttribute("D"), Map("foo" -> Literal.create("?", StringType))),
      ("EL", UnresolvedAttribute("E"), Map("*" -> Literal.create("something", StringType))),
      ("FL", UnresolvedAttribute("F"),
        Map("foo" -> Literal.create(null, NullType))),
      ("GL", UnresolvedAttribute("G"),
        Map("foo" -> Literal.create(123, LongType))),
      ("HL", UnresolvedAttribute("H"),
        Map("foo" -> Literal.create(1.23, DoubleType)))
      )

    assertAnnotatedProjection(expected)(actual)
  }

  test("create table with invalid annotation position") {
    assertFailingQuery[SapParserException]("CREATE VIEW aview AS SELECT A @ (foo = 'bar') " +
      "AS AL FROM atable")
  }

  test("create table with invalid key type") {
    assertFailingQuery[SapParserException]("CREATE VIEW aview AS " +
      "SELECT A AS AL @ (111 = 'bar') FROM atable")
  }

  test("create table with invalid value") {
    assertFailingQuery[SapParserException]("CREATE VIEW aview AS " +
      "SELECT A AS AL @ (key = !@!) FROM atable")
  }

  test("create table with two identical keys") {
    assertFailingQuery[AnalysisException]("CREATE VIEW aview AS " +
      "SELECT A AS AL @ (key1 = 'val1', key1 = 'val2', key2 = 'val1') FROM atable",
      "duplicate keys found: key1")
  }

  test("create table with malformed annotation") {
    assertFailingQuery[SapParserException]("CREATE VIEW aview " +
      "AS SELECT A AS AL @ (key2 ^ 'val1') FROM atable")
  }

  test("create table with an annotation without value") {
    assertFailingQuery[SapParserException]("CREATE VIEW aview " +
      "AS SELECT A AS AL @ (key = ) FROM atable")
  }

  test("create dimension view") {
    val parser = new SapParserDialect
    val result = parser.parse("CREATE DIMENSION VIEW myview AS SELECT 1 FROM mytable")
    val expected = CreateNonPersistentViewCommand(
      Dimension, TableIdentifier("myview"),
      Project(AliasUnresolver(Literal(1)),
        UnresolvedRelation(TableIdentifier("mytable"))), temporary = false)
    comparePlans(expected, result)
  }

  test("create incorrect dimension view is handled correctly") {
    assertFailingQuery[SapParserException]("CREATE DIMENSI VIEW myview AS SELECT 1 FROM mytable")
    assertFailingQuery[SapParserException]("CREATE DIMENSION myview AS SELECT 1 FROM mytable")
  }

  /**
    * Utility method that creates a [[SapParserDialect]] parser and tries to parse the given
    * query, it expects the parser to fail with the exception type parameter and the exception
    * message should contain the text defined in the message parameter.
    *
    * @param query The query.
    * @param message Part of the expected error message.
    * @tparam T The exception type.
    */
  def assertFailingQuery[T <: Exception: ClassTag: TypeTag](query: String,
                                                   message: String = "Syntax error"): Unit = {
    val parser = new SapParserDialect
    val ex = intercept[T] {
      parser.parse(query)
    }
    assert(ex.getMessage.contains(message))
  }

}
