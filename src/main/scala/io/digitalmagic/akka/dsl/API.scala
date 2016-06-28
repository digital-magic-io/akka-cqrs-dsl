package io.digitalmagic.akka.dsl

import akka.actor.{ActorRef, ActorSelection}

import scala.language.{implicitConversions, postfixOps}
import scala.reflect.ClassTag
import scala.util.Either

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

  implicit class PimpedActorRef(val actorRef: ActorRef) {
    def query[T](msg: Query[T])(implicit tag: ClassTag[T]) = DSL.QueryProcessor[T](Left(actorRef), msg)

    def command[T](msg: Command[T])(implicit tag: ClassTag[T]) = DSL.CommandProcessor[T](Left(actorRef), msg)
  }

  implicit class PimpedActorSelection(val actorSelection: ActorSelection) {
    def query[T](msg: Query[T])(implicit tag: ClassTag[T]) = DSL.QueryProcessor[T](Right(actorSelection), msg)

    def command[T](msg: Command[T])(implicit tag: ClassTag[T]) = DSL.CommandProcessor[T](Right(actorSelection), msg)
  }

}
