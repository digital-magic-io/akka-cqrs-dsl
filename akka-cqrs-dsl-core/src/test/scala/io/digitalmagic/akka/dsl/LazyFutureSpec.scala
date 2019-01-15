package io.digitalmagic.akka.dsl

import org.specs2.{ScalaCheck, Specification}
import scalaz._
import Scalaz._
import org.scalacheck.Arbitrary
import scalaz.scalacheck.ScalazProperties._

import scala.concurrent.{Await, Future}
import scala.concurrent.duration._

class LazyFutureSpec extends Specification with ScalaCheck { def is =
  s2"""
     LazyFuture must pass Monad laws $e1
    """

  implicit def arbitraryLazyFuture[T](implicit arb: Arbitrary[T]): Arbitrary[LazyFuture[T]] = Arbitrary[LazyFuture[T]] {
    arb.arbitrary.map(a => LazyFuture(implicit ec => Future(a)))
  }

  implicit def equalLazyFuture[T](implicit eq: Equal[T]): Equal[LazyFuture[T]] = new Equal[LazyFuture[T]] {
    import scala.concurrent.ExecutionContext.Implicits.global
    override def equal(a1: LazyFuture[T], a2: LazyFuture[T]): Boolean = Await.result(for {
      x1 <- a1(global)
      x2 <- a2(global)
    } yield x1 === x2, 1 second)
  }

  def e1 = monad.laws[LazyFuture]
}
