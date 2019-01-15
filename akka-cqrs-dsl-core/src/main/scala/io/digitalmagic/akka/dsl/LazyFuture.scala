package io.digitalmagic.akka.dsl

import scalaz._
import Scalaz._

import scala.concurrent.{ExecutionContext, Future}

case class LazyFuture[T](f: ExecutionContext => Future[T]) extends AnyRef with Function[ExecutionContext, Future[T]] {
  override def apply(ec: ExecutionContext): Future[T] = f(ec)
}

object LazyFuture {
  implicit val lazyFutureMonad: Monad[LazyFuture] = new Monad[LazyFuture] {
    override def map[A, B](fa: LazyFuture[A])(f: A => B): LazyFuture[B] = LazyFuture(implicit ec => fa(ec).map(f))
    override def point[A](a: => A): LazyFuture[A] = LazyFuture(implicit ec => Future(a))
    override def ap[A, B](fa: => LazyFuture[A])(f: => LazyFuture[A => B]): LazyFuture[B] = LazyFuture(implicit ec => fa(ec) <*> f(ec))
    override def bind[A, B](fa: LazyFuture[A])(f: A => LazyFuture[B]): LazyFuture[B] = LazyFuture(implicit ec => fa(ec) >>= { a => f(a)(ec) })
  }
}
