package zio.parser

import zio.Chunk
import zio.parser.Parser.ParserError
import zio.parser.target.Target

/** Syntax defines a parser and a printer together and provides combinators to simultaneously build them up from smaller
  * syntax fragments.
  *
  * @tparam Err
  *   Custom error type
  * @tparam In
  *   Element type of the input stream of parsing
  * @tparam Out
  *   Element type of the output stream of printing
  * @tparam Value
  *   The type of the value to be printed
  * @tparam Result
  *   The type of the parsed result value
  */
class Syntax[+Err, -In, +Out, -Value, +Result] private (
    val asParser: Parser[Err, In, Result],
    val asPrinter: Printer[Err, Out, Value, Result]
) { self =>

  /** Maps the parser with the given function 'f' */
  final def mapParser[Err2 >: Err, In2, Result2 >: Result](f: Parser[Err, In, Result] => Parser[Err2, In2, Result2]) =
    new Syntax(f(asParser), asPrinter)

  /** Maps the printer with the given function 'f' */
  final def mapPrinter[Err2 >: Err, Out2, Value2 <: Value, Result2 >: Result](
      f: Printer[Err, Out, Value, Result] => Printer[Err2, Out2, Value2, Result2]
  ) =
    new Syntax(asParser, f(asPrinter))

  /** Maps the parser's successful result with the given function 'to', and maps the value to be printed with the given
    * function 'from'
    */
  final def transform[Value2, Result2](
      to: Result => Result2,
      from: Value2 => Value
  ): Syntax[Err, In, Out, Value2, Result2] =
    new Syntax(
      asParser.map(to),
      asPrinter.transform(to, from)
    )

  /** Sets the result to 'result' and the value to be printed to 'value' */
  final def asPrinted[Result2](result: Result2, value: Value): Syntax[Err, In, Out, Result2, Result2] =
    new Syntax(
      asParser.as(result),
      asPrinter.asPrinted(result, value)
    )

  /** Maps the parser's successful result with the given function 'to', and maps the value to be printed with the given
    * function 'from'. Both of the mapping functions can fail the parser/printer.
    */
  final def transformEither[Err2, Value2, Result2](
      to: Result => Either[Err2, Result2],
      from: Value2 => Either[Err2, Value]
  ): Syntax[Err2, In, Out, Value2, Result2] =
    new Syntax(
      asParser.transformEither(to),
      asPrinter.transformEither(to, from)
    )

  /** Maps the parser's successful result with the given function 'to', and maps the value to be printed with the given
    * function 'from'. Both of the mapping functions can fail the parser/printer. The failure is indicated in the error
    * channel by the value None.
    */
  final def transformOption[Value2, Result2](
      to: Result => Option[Result2],
      from: Value2 => Option[Value]
  ): Syntax[Option[Err], In, Out, Value2, Result2] =
    transformEither[Option[Err], Value2, Result2](
      value => to(value).toRight(None),
      value => from(value).toRight(None)
    )

  /** Maps the parsed value with the function 'to', and the value to be printed with the partial function 'from'. It the
    * partial function is not defined on the value, the printer fails with 'failure'.
    *
    * This can be used to define separate syntaxes for subtypes, that can be later combined.
    */
  final def transformTo[Err2 >: Err, Value2, Result2](
      to: Result => Result2,
      from: PartialFunction[Value2, Value],
      failure: Err2
  ): Syntax[Err2, In, Out, Value2, Result2] =
    transformEither(
      to.andThen(Right.apply),
      (d0: Value2) => from.lift(d0).fold[Either[Err2, Value]](Left(failure))(Right.apply)
    )

  /** Symbolic alias for zipLeft */
  final def <~[Err2 >: Err, In2 <: In, Out2 >: Out](
      that: => Syntax[Err2, In2, Out2, Unit, Any]
  ): Syntax[Err2, In2, Out2, Value, Result] =
    zipLeft(that)

  /** Concatenates this parser with 'that' parser. In case both parser succeeds, the result is the result of this
    * parser. Otherwise the parser fails. The printer passes the value to be printed to this printer, and also executes
    * 'that' printer with unit as the input value.
    *
    * Note that the right syntax must have 'Value' defined as Unit, because there is no way for the printer to
    * reconstruct an arbitrary input for the right printer.
    */
  final def zipLeft[Err2 >: Err, In2 <: In, Out2 >: Out](
      that: => Syntax[Err2, In2, Out2, Unit, Any]
  ): Syntax[Err2, In2, Out2, Value, Result] =
    new Syntax(
      asParser.zipLeft(that.asParser),
      asPrinter.zipLeft(that.asPrinter)
    )

  /** Specifies a filter condition 'condition' that gets checked in both parser and printer mode and in case it
    * evaluates to false, fails with 'failure'.
    */
  final def filter[Err2 >: Err, Value2 <: Value](condition: Value2 => Boolean, failure: Err2)(implicit
      ev: Result <:< Value2
  ): Syntax[Err2, In, Out, Value2, Value2] =
    transformEither(
      (d2: Result) => if (condition(ev(d2))) Right(d2) else Left(failure),
      (d1: Value2) => if (condition(d1)) Right(d1) else Left(failure)
    )

  /** Associates a name with this syntax. The chain of named parsers are reported in case of failure to help debugging
    * parser issues.
    */
  final def named(name: String): Syntax[Err, In, Out, Value, Result] =
    new Syntax(
      asParser.named(name),
      asPrinter.named(name)
    )

  /** Symbolic alias for named */
  final def ??(name: String): Syntax[Err, In, Out, Value, Result] = named(name)

  /** Symbolic alias for orElse */
  final def |[Err2 >: Err, In2 <: In, Out2 >: Out, Value2 <: Value, Result2 >: Result](
      that: => Syntax[Err2, In2, Out2, Value2, Result2]
  ): Syntax[Err2, In2, Out2, Value2, Result2] = orElse(that)

  /** Symbolic alias for orElse */
  final def <>[Err2 >: Err, In2 <: In, Out2 >: Out, Value2 <: Value, Result2 >: Result](
      that: => Syntax[Err2, In2, Out2, Value2, Result2]
  ): Syntax[Err2, In2, Out2, Value2, Result2] = orElse(that)

  /** Assigns 'that' syntax as a fallback of this. First this parser or printer gets evaluated. In case it succeeds, the
    * result is this syntax's result. In case it fails, the result is 'that' syntax's result.
    *
    * If auto-backtracking is on, this parser will backtrack before trying 'that' parser.
    */
  final def orElse[Err2 >: Err, In2 <: In, Out2 >: Out, Value2 <: Value, Result2 >: Result](
      that: => Syntax[Err2, In2, Out2, Value2, Result2]
  ): Syntax[Err2, In2, Out2, Value2, Result2] =
    new Syntax(
      asParser.orElse(that.asParser),
      asPrinter.orElse(that.asPrinter)
    )

  /** Symbolic alias for orElseEither */
  final def <+>[Err2 >: Err, In2 <: In, Out2 >: Out, Value2, Result2](
      that: => Syntax[Err2, In2, Out2, Value2, Result2]
  ): Syntax[Err2, In2, Out2, Either[Value, Value2], Either[Result, Result2]] =
    orElseEither(that)

  /** Assigns 'that' syntax as a fallback of this. First this parser or printer gets evaluated. In case it succeeds, the
    * result is this syntax's result wrapped in 'Left'. In case it fails, the result is 'that' syntax's result, wrapped
    * in 'Right'.
    *
    * If auto-backtracking is on, this parser will backtrack before trying 'that' parser.
    */
  final def orElseEither[Err2 >: Err, In2 <: In, Out2 >: Out, Value2, Result2](
      that: => Syntax[Err2, In2, Out2, Value2, Result2]
  ): Syntax[Err2, In2, Out2, Either[Value, Value2], Either[Result, Result2]] =
    new Syntax(
      asParser.orElseEither(that.asParser),
      asPrinter.orElseEither(that.asPrinter)
    )

  /** This syntax repeated at least 'min' times.
    *
    * The result is all the parsed elements until the first failure. The failure that stops the repetition gets
    * swallowed and in case auto-backtracking is on, the parser backtracks to the end of the last successful item.
    *
    * When printing, the input is a chunk of values and each element gets printed.
    */
  final def atLeast(min: Int): Syntax[Err, In, Out, Chunk[Value], Chunk[Result]] =
    new Syntax(
      asParser.atLeast(min),
      asPrinter.repeat
    )

  /** This syntax repeated at least once.
    *
    * The result is all the parsed elements until the first failure. The failure that stops the repetition gets
    * swallowed and in case auto-backtracking is on, the parser backtracks to the end of the last successful item.
    *
    * When printing, the input is a chunk of values and each element gets printed.
    */
  final def repeat: Syntax[Err, In, Out, Chunk[Value], Chunk[Result]] =
    new Syntax(
      asParser.repeat,
      asPrinter.repeat
    )

  /** Symbolic alias for repeat */
  final def + : Syntax[Err, In, Out, Chunk[Value], Chunk[Result]] = repeat

  /** This syntax repeated zero or more times.
    *
    * The result is all the parsed elements until the first failure. The failure that stops the repetition gets
    * swallowed and in case auto-backtracking is on, the parser backtracks to the end of the last successful item.
    *
    * When printing, the input is a chunk of values and each element gets printed.
    */
  final def repeat0: Syntax[Err, In, Out, Chunk[Value], Chunk[Result]] =
    new Syntax(
      asParser.repeat0,
      asPrinter.repeat0
    )

  /** Symbolic alias for repeat0 */
  final def * : Syntax[Err, In, Out, Chunk[Value], Chunk[Result]] = repeat0

  /** Repeats this syntax at least once and with 'sep' injected between each element */
  final def repeatWithSep[Err2 >: Err, In2 <: In, Out2 >: Out](
      sep: Syntax[Err2, In2, Out2, Unit, Unit]
  ): Syntax[Err2, In2, Out2, Chunk[Value], Chunk[Result]] =
    (self ~ (sep ~> self).repeat0).transform(
      { case (head, tail) => head +: tail },
      chunk => (chunk.head, chunk.tail)
    )

  /** Repeats this syntax zero or more times and with 'sep' injected between each element */
  final def repeatWithSep0[Err2 >: Err, In2 <: In, Out2 >: Out](
      sep: Syntax[Err2, In2, Out2, Unit, Unit]
  ): Syntax[Err2, In2, Out2, Chunk[Value], Chunk[Result]] =
    (self ~ (sep ~> self).repeat0).optional
      .transform(
        {
          case Some((head, tail)) => head +: tail
          case None               => Chunk.empty
        },
        chunk => chunk.headOption.map(head => (head, chunk.tail))
      )

  /** Repeats this syntax until 'stopCondition', performed after each element, results in success. */
  final def repeatUntil[Err2 >: Err, In2 <: In, Out2 >: Out](
      stopCondition: Syntax[Err2, In2, Out2, Unit, Unit]
  ): Syntax[Err2, In2, Out2, Chunk[Value], Chunk[Result]] =
    new Syntax(
      asParser.repeatUntil(stopCondition.asParser),
      asPrinter.repeatUntil(stopCondition.asPrinter)
    )

  /** Make this syntax optional.
    *
    * Failure of the parser will be ignored. In case auto-backtracking is enabled, backtracking is performed on it.
    */
  final def optional: Syntax[Err, In, Out, Option[Value], Option[Result]] =
    new Syntax(
      asParser.optional,
      asPrinter.optional
    )

  /** Symbolic alias for optional */
  final def ? : Syntax[Err, In, Out, Option[Value], Option[Result]] = optional

  /** Syntax that succeeds only if this syntax fails */
  final def not[Err2 >: Err](failure: Err2): Syntax[Err2, In, Out, Value, Unit] =
    new Syntax(
      asParser.not(failure),
      asPrinter.unit
    )

  /** Concatenates the syntaxes 'left', then this, then 'right'.
    *
    * All three must succeed. The result is this syntax's result.
    *
    * Note that the 'left' and 'right' syntaxes must have a 'Value' type of Unit. Otherwise the printer could not
    * produce an arbitrary input value for them as their result is discarded.
    */
  final def between[Err2 >: Err, In2 <: In, Out2 >: Out](
      left: Syntax[Err2, In2, Out2, Unit, Any],
      right: Syntax[Err2, In2, Out2, Unit, Any]
  ): Syntax[Err2, In2, Out2, Value, Result] =
    (left ~ self ~ right).transform(
      { case (_, value, _) => value },
      (value: Value) => value
    )

  /** Surrounds this parser with the 'other' parser. The result is this parser's result. */
  final def surroundedBy[Err2 >: Err, In2 <: In, Out2 >: Out](
      other: Syntax[Err2, In2, Out2, Unit, Any]
  ): Syntax[Err2, In2, Out2, Value, Result] =
    (other ~ self ~ other).transform(
      { case (_, value, _) => value },
      (value: Value) => value
    )

  /** Maps the error with the given function 'f' */
  final def mapError[Err2](f: Err => Err2): Syntax[Err2, In, Out, Value, Result] =
    new Syntax(
      asParser.mapError(f),
      asPrinter.mapError(f)
    )

  /** Ignores this syntax's result and instead captures the parsed string fragment / directly print the input string */
  final def string(implicit
      ev1: Char <:< In
  ): Syntax[Err, Char, Char, String, String] =
    new Syntax(
      asParser.string,
      Printer.anyString
    )

  /** Flattens a result of parsed strings to a single string */
  final def flatten(implicit
      ev1: Chunk[String] <:< Value,
      ev2: Result <:< Chunk[String]
  ): Syntax[Err, In, Out, String, String] =
    transform(s => s.mkString, (s: String) => ev1(Chunk(s)))

  /** Widen this syntax's printed value type by specifying a partial function to narrow it back to the set of supported
    * subtypes.
    */
  final def widenWith[Err2 >: Err, Result2](narrow: PartialFunction[Result2, Value], failure: Err2)(implicit
      ev: Result <:< Result2
  ): Syntax[Err2, In, Out, Result2, Result2] =
    transformTo(
      (value: Result) => ev(value),
      narrow,
      failure
    )

  /** Maps the result of the syntax with the given function 'f' */
  final def map[Result2](f: Result => Result2): Syntax[Err, In, Out, Value, Result2] =
    self.transformEither(f.andThen(Right.apply), (value: Value) => Right(value))

  /** Syntax that does not do anything and results in unit */
  final def unit: Syntax[Err, In, Out, Value, Unit] =
    new Syntax(
      self.asParser.unit,
      self.asPrinter.unit
    )

  /** Syntax that does not consume any input but prints 'printed' and results in unit */
  final def unit(printed: Value): Syntax[Err, In, Out, Unit, Unit] =
    new Syntax(
      self.asParser.unit,
      self.asPrinter.asPrinted((), printed)
    )

  /** Converts a Chunk syntax to a List syntax */
  final def toList[Item](implicit
      ev1: Chunk[Item] <:< Value,
      ev2: Result <:< Chunk[Item]
  ): Syntax[Err, In, Out, List[Item], List[Item]] =
    self.transform(
      ev2(_).toList,
      list => ev1(Chunk.fromIterable(list))
    )

  /** Syntax that resets the parsing position in case it fails.
    *
    * By default backtracking points are automatically inserted. This behavior can be changed with the autoBacktracking,
    * manualBacktracking and setAutoBacktracking combinators.
    *
    * Does not affect printing.
    */
  def backtrack: Syntax[Err, In, Out, Value, Result] =
    new Syntax(
      asParser.backtrack,
      asPrinter
    )

  /** Enables auto-backtracking for this syntax */
  final def autoBacktracking: Syntax[Err, In, Out, Value, Result] =
    new Syntax(
      asParser.autoBacktracking,
      asPrinter
    )

  /** Disables auto-backtracking for this syntax */
  final def manualBacktracking: Syntax[Err, In, Out, Value, Result] =
    new Syntax(
      asParser.manualBacktracking,
      asPrinter
    )

  /** Enables or disables auto-backtracking for this syntax */
  final def setAutoBacktracking(enabled: Boolean): Syntax[Err, In, Out, Value, Result] =
    new Syntax(
      asParser.setAutoBacktracking(enabled),
      asPrinter
    )

  /** Run this parser on the given 'input' string */
  final def parseString(input: String)(implicit ev: Char <:< In): Either[ParserError[Err], Result] =
    asParser.parseString(input)

  /** Run this parser on the given 'input' string using a specific parser implementation */
  final def parseString(input: String, parserImplementation: ParserImplementation)(implicit
      ev: Char <:< In
  ): Either[ParserError[Err], Result] =
    asParser.parseString(input, parserImplementation)

  /** Run this parser on the given 'input' chunk of characters */
  final def parseChars(input: Chunk[Char])(implicit ev: Char <:< In): Either[ParserError[Err], Result] =
    asParser.parseChars(input)

  /** Run this parser on the given 'input' chunk of characters using a specific parser implementation */
  final def parseChars(input: Chunk[Char], parserImplementation: ParserImplementation)(implicit
      ev: Char <:< In
  ): Either[ParserError[Err], Result] = asParser.parseChars(input, parserImplementation)

  /** Prints a value 'value' to the target implementation 'target' */
  final def print[Out2 >: Out](value: Value, target: Target[Out2]): Either[Err, Unit] =
    asPrinter.print(value, target, Map.empty)

  /** Prints a value 'value' and get back the chunk of output elements' */
  final def print(value: Value): Either[Err, Chunk[Out]] =
    asPrinter.print(value, Map.empty)

  /** Prints a value 'value' to a string */
  final def printString(value: Value, colorMap: Map[String, String] = Map.empty)(implicit
      ev: Out <:< Char
  ): Either[Err, String] =
    asPrinter.printString(value, colorMap)

  /** Strips all the name information from this syntax to improve performance but reduces the failure message's
    * verbosity.
    */
  def strip: Syntax[Err, In, Out, Value, Result] =
    new Syntax(
      asParser.strip,
      asPrinter
    )
}

object Syntax {
  private[parser] def from[Err, In, Out, Value, Result](
      parser: Parser[Err, In, Result],
      printer: Printer[Err, Out, Value, Result]
  ): Syntax[Err, In, Out, Value, Result] =
    new Syntax(parser, printer)

  /** Syntax that does not parse or print anything but succeeds with 'value' */
  def succeed[Result](value: Result): Syntax[Nothing, Any, Nothing, Any, Result] =
    new Syntax(Parser.Succeed(value), Printer.Succeed(value))

  /** Syntax that does not pares or print anything but fails with 'failure' */
  def fail[Err](failure: Err): Syntax[Err, Any, Nothing, Any, Nothing] =
    new Syntax(Parser.Fail(failure), Printer.Fail(failure))

  // Char variants
  /** Parse or print a specific character 'value' and result in unit */
  def char(value: Char): Syntax[String, Char, Char, Unit, Unit] =
    char(value, s"not '$value'")

  /** Parse or print a specific character 'value' or fail with 'failure', result in unit */
  def char[Err](value: Char, failure: Err): Syntax[Err, Char, Char, Unit, Unit] =
    regexDiscard(Regex.charIn(value), failure, Chunk(value))

  /** Parse or print a single character and fail if it is 'value' */
  def notChar(value: Char): Syntax[String, Char, Char, Char, Char] =
    notChar(value, s"cannot be '$value'")

  /** Parse or print a single character and fail with 'failure' if it is 'value' */
  def notChar[Err](value: Char, failure: Err): Syntax[Err, Char, Char, Char, Char] =
    regexChar(Regex.charNotIn(value), failure)

  /** Parse with a given regular expression and discard its results. If the regex fails, fail with 'failure'. When
    * printing, the chunk of characters in 'value' gets printed.
    */
  def regexDiscard[Err](regex: Regex, failure: Err, value: Chunk[Char]): Syntax[Err, Char, Char, Unit, Unit] =
    new Syntax(
      Parser.regexDiscard(regex, failure),
      Printer.regexDiscard(regex, value)
    )

  /** Parse with a given regular expression and discard its results. The regex should never fail. When printing, the
    * chunk of characters in 'value' gets printed.
    */
  def unsafeRegexDiscard(regex: Regex, value: Chunk[Char]): Syntax[Nothing, Char, Char, Unit, Unit] =
    new Syntax(
      Parser.unsafeRegexDiscard(regex),
      Printer.regexDiscard(regex, value)
    )

  /** Syntax that during parsing executes a regular expression on the input and results in the last parsed character, or
    * fails with 'failure'. Useful for regexes that are known to parse a single character. The printer prints the
    * character provided as input value.
    */
  def regexChar[Err](regex: Regex, failure: Err): Syntax[Err, Char, Char, Char, Char] =
    new Syntax(
      Parser.ParseRegexLastChar(regex, Some(failure)),
      Printer.ParseRegexLastChar(regex, Some(failure))
    )

  /** Syntax that during parsing executes a regular expression on the input and results in the last parsed character.
    * The regex should never fail. Useful for regexes that are known to parse a single character. The printer prints the
    * character provided as input value.
    */
  def unsafeRegexChar[Err](regex: Regex) =
    new Syntax(
      Parser.ParseRegexLastChar(regex, None),
      Printer.ParseRegexLastChar(regex, None)
    )

  /** Syntax that executes a regular expression on the input and results in the chunk of the parsed characters, or fails
    * with 'failure'.
    */
  def regex[Err](regex: Regex, failure: Err): Syntax[Err, Char, Char, Chunk[Char], Chunk[Char]] =
    new Syntax(
      Parser.ParseRegex(regex, Some(failure)),
      Printer.ParseRegex(regex, Some(failure))
    )

  /** Syntax that executes a regular expression on the input and results in the chunk of the parsed characters. The
    * regex should never fail.
    */
  def unsafeRegex[Err](regex: Regex): Syntax[Nothing, Char, Char, Chunk[Char], Chunk[Char]] =
    new Syntax(
      Parser.ParseRegex(regex, None),
      Printer.ParseRegex(regex, None)
    )

  /** Syntax that parses/prints a single character */
  val anyChar: Syntax[Nothing, Char, Char, Char, Char] =
    unsafeRegexChar(
      Regex.anyChar
    )

  /** Syntax that parses/prints a single character that matches one of 'chars' */
  def charIn(chars: Char*): Syntax[String, Char, Char, Char, Char] =
    regexChar(Regex.charIn(chars: _*), s"Not the expected character (${chars.mkString(", ")})")

  /** Syntax that parses/prints a single character that matches one of the characters in 'chars' */
  def charIn(chars: String): Syntax[String, Char, Char, Char, Char] =
    charIn(chars.toSeq: _*)

  /** Syntax that parses/prints a single character that does not match any of 'chars' */
  def charNotIn(chars: Char*): Syntax[String, Char, Char, Char, Char] =
    regexChar(Regex.charNotIn(chars: _*), s"One of the excluded characters (${chars.mkString(", ")})")

  /** Syntax that parses/prints a single character that does not match any of the characters in 'chars' */
  def charNotIn(chars: String): Syntax[String, Char, Char, Char, Char] =
    charNotIn(chars.toSeq: _*)

  /** Syntax that parses/prints a single character that matches the given predicate, or fails with 'failure' */
  def filterChar[Err](filter: Char => Boolean, failure: Err): Syntax[Err, Char, Char, Char, Char] =
    regexChar(Regex.filter(filter), failure)

  /** Syntax that parses/prints an arbitrary long string */
  lazy val anyString: Syntax[Nothing, Char, Char, String, String] =
    from(
      Parser.anyString,
      Printer.anyString
    )

  /** Syntax that parses/prints a specific string 'str', and results in 'value' */
  def string[Result](str: String, value: Result): Syntax[String, Char, Char, Result, Result] =
    regexDiscard(Regex.string(str), s"Not '$str'", Chunk.fromArray(str.toCharArray)).as(value)

  /** Syntax that results in unit */
  lazy val unit: Syntax[Nothing, Any, Nothing, Any, Unit] = succeed(())

  /** Syntax of a single alpha-numeric character */
  lazy val alphaNumeric: Syntax[String, Char, Char, Char, Char] = regexChar(Regex.anyAlphaNumeric, "not alphanumeric")

  /** Syntax of a single digit */
  lazy val digit: Syntax[String, Char, Char, Char, Char] = regexChar(Regex.anyDigit, "not a digit")

  /** Syntax of a single letter */
  lazy val letter: Syntax[String, Char, Char, Char, Char] = regexChar(Regex.anyLetter, "not a letter")

  /** Syntax of a single whitespace character */
  lazy val whitespace: Syntax[String, Char, Char, Char, Char] = regexChar(Regex.whitespace, "not a whitespace")

  /** Syntax that in parser mode results in the current input stream position */
  lazy val index: Syntax[Nothing, Any, Nothing, Any, Int] =
    from(
      Parser.index,
      Printer.succeed(0)
    )

  /** Syntax that in parser mode only succeeds if the input stream has been consumed fully.
    *
    * This can be used to require that a parser consumes the full input.
    */
  lazy val end: Syntax[Nothing, Any, Nothing, Any, Unit] =
    from(
      Parser.end,
      Printer.unit
    )
}
