package org.apache.spark.sql.currency.erp

import org.apache.spark.sql.catalyst.InternalRow
import org.apache.spark.sql.catalyst.expressions.codegen.CodegenFallback
import org.apache.spark.sql.catalyst.expressions.{Expression, ImplicitCastInputTypes}
import org.apache.spark.sql.currency.CurrencyConversionException
import org.apache.spark.sql.currency.erp.ERPConversionLoader.RConversionOptionsCurried
import org.apache.spark.sql.types.{AbstractDataType, DataType, DoubleType, StringType}
import org.apache.spark.unsafe.types.UTF8String

import scala.util.control.NonFatal


/**
 * This expression uses the [[com.sap.hl.currency.api.CurrencyConversionProvider]]
 * to convert currencies, if this class is on the class path.
 *
 * @param conversionFunction the conversion closure initialized on the spark driver
 * @param children child expressions
 */
case class ERPCurrencyConversionExpression(
    conversionFunction: RConversionOptionsCurried,
    children: Seq[Expression])
  extends Expression
  with ImplicitCastInputTypes
  with CodegenFallback {

  protected val CLIENT_INDEX = 0
  protected val CONVERSION_TYPE_INDEX = 1
  protected val AMOUNT_INDEX = 2
  protected val FROM_INDEX = 3
  protected val TO_INDEX = 4
  protected val DATE_INDEX = 5
  protected val NUM_ARGS = 6

  protected val errorMessage = "Currency conversion library encountered an internal error"


  override def eval(input: InternalRow): Any = {
    val inputArguments = children.map(_.eval(input))

    require(inputArguments.length == NUM_ARGS, "wrong number of arguments")

    // parse arguments
    val client = Option(inputArguments(CLIENT_INDEX).asInstanceOf[UTF8String]).map(_.toString)
    val conversionType =
      Option(inputArguments(CONVERSION_TYPE_INDEX).asInstanceOf[UTF8String]).map(_.toString)
    val amount = Option(inputArguments(AMOUNT_INDEX).asInstanceOf[Double])
    val sourceCurrency =
      Option(inputArguments(FROM_INDEX).asInstanceOf[UTF8String]).map(_.toString)
    val targetCurrency = Option(inputArguments(TO_INDEX).asInstanceOf[UTF8String]).map(_.toString)
    val date = Option(inputArguments(DATE_INDEX).asInstanceOf[UTF8String]).map(_.toString)

    // perform conversion
    val conversion =
      conversionFunction(client, conversionType, sourceCurrency, targetCurrency, date)
    val resultTry = conversion(amount)

    // If 'resultTry' holds a 'Failure', we have to propagate it because potential failure
    // handling already took place. We just wrap it in case it is a cryptic error.
    resultTry.recover {
      case NonFatal(err) => throw new CurrencyConversionException(errorMessage, err)
    }.get.orNull
  }

  override def dataType: DataType = DoubleType

  override def nullable: Boolean = true

  override def inputTypes: Seq[AbstractDataType] =
    Seq(StringType, StringType, DoubleType, StringType, StringType, StringType)

  def inputNames: Seq[String] =
    Seq("client", "conversion_type", "amount", "source", "target", "date")

  def getChild(name: String): Option[Expression] = {
    inputNames.zip(children).find { case (n, _) => name == n }.map(_._2)
  }
}
