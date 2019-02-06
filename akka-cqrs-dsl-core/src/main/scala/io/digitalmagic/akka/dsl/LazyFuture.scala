package io.digitalmagic.akka.dsl

import scalaz._
import Scalaz._

import scala.concurrent.{ExecutionContext, Future}

case class LazyFuture[T](f: ExecutionContext => Future[T]) extends AnyVal {
  def apply(ec: ExecutionContext): Future[T] = f(ec)

  def map[S](f: T => S): LazyFuture[S] =
    LazyFuture(implicit ec => this(ec).map(f))
  def flatMap[S](f: T => LazyFuture[S]): LazyFuture[S] =
    LazyFuture(implicit ec => this(ec).flatMap(f(_)(ec)))
  def recover[U >: T](pf: PartialFunction[Throwable, U]): LazyFuture[U] =
    LazyFuture(implicit ec => this(ec).recover(pf))
  def recoverWith[U >: T](pf: PartialFunction[Throwable, LazyFuture[U]]): LazyFuture[U] =
    LazyFuture(implicit ec => this(ec).recoverWith { case t if pf.isDefinedAt(t) => pf(t)(ec) } )
  def fallbackTo[U >: T](that: LazyFuture[U]): LazyFuture[U] =
    LazyFuture(implicit ec => this(ec).fallbackTo(that(ec)))
  def transform[S](s: T => S, f: Throwable => Throwable) =
    LazyFuture(implicit ec => this(ec).transform(s, f))
  def mapError(f: Throwable => Throwable): LazyFuture[T] =
    transform(identity, f)

  implicit def toFuture(implicit ec: ExecutionContext): Future[T] = f(ec)
}

object LazyFuture {
  implicit val lazyFutureMonad: Monad[LazyFuture] = new Monad[LazyFuture] {
    override def map[A, B](fa: LazyFuture[A])(f: A => B): LazyFuture[B] = fa.map(f)
    override def point[A](a: => A): LazyFuture[A] = LazyFuture(implicit ec => Future(a))
    override def ap[A, B](fa: => LazyFuture[A])(f: => LazyFuture[A => B]): LazyFuture[B] = LazyFuture(implicit ec => fa(ec) <*> f(ec))
    override def bind[A, B](fa: LazyFuture[A])(f: A => LazyFuture[B]): LazyFuture[B] = fa.flatMap(f)
  }

  def failed[T](exception: Throwable): LazyFuture[T] = LazyFuture(_ => Future.failed(exception))
  def successful[T](result: T): LazyFuture[T] = LazyFuture(_ => Future.successful(result))
}
