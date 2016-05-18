package io.digitalmagic.akka.dsl

import scala.reflect.ClassTag

object API {

  trait ResponseError extends Throwable

  case object NotFound extends ResponseError

  type Result[T] = Either[ResponseError, T]

  trait Response[T] {
    def value: Either[ResponseError, T]
  }

  trait Request[T] {
    type Result <: Response[T]
    def failure(error: ResponseError)(implicit tag: ClassTag[T]): Result
  }

  case class CommandResult[T](override val value: Either[ResponseError, T])(implicit tag: ClassTag[T]) extends Response[T]
  case class QueryResult[T](override val value: Either[ResponseError, T])(implicit tag: ClassTag[T]) extends Response[T]

  trait Command[T] extends Request[T] {
    override type Result = CommandResult[T]
    def success(value: T)(implicit tag: ClassTag[T]): CommandResult[T] = CommandResult(Right(value))
    override def failure(error: ResponseError)(implicit tag: ClassTag[T]): CommandResult[T] = CommandResult(Left(error))
  }

  trait Query[T] extends Request[T] {
    override type Result = QueryResult[T]
    def found(value: T)(implicit tag: ClassTag[T]): QueryResult[T] = QueryResult(Right(value))
    def notFound(implicit tag: ClassTag[T]): QueryResult[T] = QueryResult(Left(NotFound))
    override def failure(error: ResponseError)(implicit tag: ClassTag[T]): QueryResult[T] = QueryResult(Left(error))
  }

}
