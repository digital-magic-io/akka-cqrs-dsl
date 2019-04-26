package io.digitalmagic.akka.dsl

import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Prop.forAll
import org.scalacheck.Test.Parameters
import org.scalatest.WordSpecLike
import org.scalatest.prop.Checkers._

class PairStringRepresentableSpec extends WordSpecLike {

  val asciiStringGen: Gen[String] = Gen.containerOf[Array, Char](Gen.choose[Char](32,127)).map(_.mkString)
  implicit val asciiStringArb: Arbitrary[String] = Arbitrary(asciiStringGen)

  "pairStringRepresentable" must {
    val parameters: Parameters = Parameters.default.withWorkers(Runtime.getRuntime.availableProcessors())
    val rep = StringRepresentable.pairStringRepresentable[String, String]

    "correctly parse its own result" in {
      check(forAll((a: String, b: String) => {
        rep.fromString(rep.asString((a, b))).contains((a, b))
      }), parameters)
    }

    "parse only string if generates the same one" in {
      check(forAll((s: String) => {
        !rep.fromString(s).exists(rep.asString(_) != s)
      }), parameters)
    }
  }
}
