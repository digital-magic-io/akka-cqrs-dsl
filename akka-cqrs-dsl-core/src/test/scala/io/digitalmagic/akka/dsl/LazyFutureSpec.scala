package io.digitalmagic.akka.dsl

import org.scalacheck.Arbitrary
import org.specs2.{ScalaCheck, Specification}
import scalaz._
import scalaz.Scalaz._
import scalaz.scalacheck.ScalazProperties._

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.{Failure, Success}

class LazyFutureSpec extends Specification with ScalaCheck { def is =
  s2"""
     LazyFuture must pass MonadError laws $e1
    """

  implicit def arbitraryLazyFuture[T](implicit arb: Arbitrary[T]): Arbitrary[LazyFuture[T]] = Arbitrary[LazyFuture[T]] {
    arb.arbitrary.map(LazyFuture.successful)
  }

  implicit def equalLazyFuture[T](implicit eq: Equal[T]): Equal[LazyFuture[T]] = new Equal[LazyFuture[T]] {
    import scala.concurrent.ExecutionContext.Implicits.global
    override def equal(a1: LazyFuture[T], a2: LazyFuture[T]): Boolean =
      Await.result(for {
        x1 <- a1(global).transformWith(_.pure[LazyFuture])
        x2 <- a2(global).transformWith(_.pure[LazyFuture])
      } yield {
        (x1, x2) match {
          case (Success(x), Success(y)) => x === y
          case (Failure(x), Failure(y)) => x.getClass == y.getClass && x.getMessage == y.getMessage
          case _ => false
        }
      }, 1 second)
  }

  def e1 = monadError.laws[LazyFuture, Throwable]
}
