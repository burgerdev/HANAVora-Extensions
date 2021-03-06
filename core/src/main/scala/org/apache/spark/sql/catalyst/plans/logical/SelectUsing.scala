package org.apache.spark.sql.catalyst.plans.logical
import org.apache.spark.SparkContext
import org.apache.spark.sql.SQLContext
import org.apache.spark.sql.catalyst.expressions.Attribute
import org.apache.spark.sql.types.StructField

/**
  * Class representing a direct sql call to a datasource implementing raw SQL interface
  * [[org.apache.spark.sql.sources.RawSqlSourceProvider]]
  */
case class SelectUsing(
    sqlCommand: String,
    className: String,
    output: Seq[Attribute])
  extends LeafNode {

  override lazy val resolved = true

  @transient override lazy val statistics: Statistics = Statistics(
    /**
      * TODO: Instead of returning a default value here, find a way to return a meaningful size
      *
      * until now: we set it to larger than `spark.sql.autoBroadcastJoinThreshold` to be sure that
      * those tables are _not_ broadcasted during a join (which is not suitable for large tables)
      */
    sizeInBytes = BigInt(SQLContext.getOrCreate(SparkContext.getOrCreate()).conf.defaultSizeInBytes)
  )
}

/**
  * Unresolved representation, will be resolved by the Analyzer rule
  * [[org.apache.spark.sql.catalyst.analysis.ResolveSelectUsing]]
  *
  * @param sqlCommand the raw SQL string to execute
  * @param className the name of the data source
  * @param fields if None, the schema gets inferred from the data source, if set, it is assumed to
  *               be the correct schema
  */
case class UnresolvedSelectUsing(
    sqlCommand: String,
    className: String,
    fields: Option[Seq[StructField]] = None)
  extends LeafNode {

  override def output: Seq[Attribute] = Nil

  override lazy val resolved = false
}
