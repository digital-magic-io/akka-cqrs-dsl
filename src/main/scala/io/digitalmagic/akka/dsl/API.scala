package io.digitalmagic.akka.dsl

import akka.actor.{ActorRef, ActorSelection}
import akka.pattern.ask
import akka.util.Timeout
import scalaz._
import Scalaz._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.Either

object API {

  trait ResponseError extends Throwable
  case object NotFound extends ResponseError
  case class InternalError(cause: Throwable) extends ResponseError

  type Result[T] = Either[ResponseError, T]

  trait Response[T] extends Product with Serializable {
    def value: Either[ResponseError, T]
  }

  trait Request[T] extends Product with Serializable {
    type Result <: Response[T]
    def success(value: T): Result
    def failure(error: ResponseError): Result
  }

  case class CommandResult[T](override val value: Either[ResponseError, T]) extends Response[T]
  case class QueryResult[T](override val value: Either[ResponseError, T]) extends Response[T]

  trait Command[T] extends Request[T] {
    override type Result = CommandResult[T]
    override def success(value: T): CommandResult[T] = CommandResult(Right(value))
    override def failure(error: ResponseError): CommandResult[T] = CommandResult(Left(error))
  }

  trait Query[T] extends Request[T] {
    override type Result = QueryResult[T]
    def found(value: T): QueryResult[T] = QueryResult(Right(value))
    def notFound: QueryResult[T] =  QueryResult(Left(NotFound))
    override def success(value: T): QueryResult[T] = found(value)
    override def failure(error: ResponseError): QueryResult[T] = QueryResult(Left(error))
  }

  case class QueryFuture[T](f: ExecutionContext => Future[T]) extends Function[ExecutionContext, Future[T]] {
    override def apply(ec: ExecutionContext): Future[T] = f(ec)
  }

  object QueryFuture {
    implicit def queryFutureFunctor: Functor[QueryFuture] = new Functor[QueryFuture] {
      override def map[A, B](fa: QueryFuture[A])(f: A => B): QueryFuture[B] = QueryFuture(implicit ec => fa(ec).map(f))
    }
  }

  implicit class PimpedActorRef(val actorRef: ActorRef) {
    def query[T](msg: Query[T])(implicit tag: ClassTag[T], akkaTimeout: Timeout = 5 seconds): QueryProcessor[T] = QueryProcessor[T](Left(actorRef), msg)

    def command[T](msg: Command[T])(implicit tag: ClassTag[T], akkaTimeout: Timeout = 5 seconds): CommandProcessor[T] = CommandProcessor[T](Left(actorRef), msg)
  }

  implicit class PimpedActorSelection(val actorSelection: ActorSelection) {
    def query[T](msg: Query[T])(implicit tag: ClassTag[T], akkaTimeout: Timeout = 5 seconds): QueryProcessor[T] = QueryProcessor[T](Right(actorSelection), msg)

    def command[T](msg: Command[T])(implicit tag: ClassTag[T], akkaTimeout: Timeout = 5 seconds): CommandProcessor[T] = CommandProcessor[T](Right(actorSelection), msg)
  }


  sealed case class QueryProcessor[T](whom: Either[ActorRef, ActorSelection], msg: Query[T])(implicit tag: ClassTag[T], val akkaTimeout: Timeout = 5 seconds) {

    private def path = whom fold(r => r.path, s => s.anchorPath)

    def withTimeout(akkaTimeout: Timeout): QueryProcessor[T] = {
      QueryProcessor[T](whom, msg)(tag, akkaTimeout)
    }

    def map[R](f: T => R)(implicit executor: ExecutionContext): Future[R] = {
      whom fold(r => r ? msg, s => s ? msg) map {
        case QueryResult(Right(x: T)) => f(x)
        case QueryResult(Left(e: ResponseError)) => throw e
        case z => throw new IllegalArgumentException(s"$path ? $msg replied with unexpected $z")
      }
    }

    def flatMap[R](f: T => Future[R])(implicit executor: ExecutionContext): Future[R] = {
      whom fold(r => r ? msg, s => s ? msg) flatMap {
        case QueryResult(Right(x: T)) => f(x)
        case QueryResult(Left(e: ResponseError)) => Future.failed(e)
        case z => Future.failed(new IllegalArgumentException(s"$path ? $msg replied with unexpected $z"))
      }
    }

    def asFuture: QueryFuture[T] = QueryFuture(implicit ex => map(identity))
  }

  implicit def queryProcessorToFuture[T](queryProcessor: QueryProcessor[T]): QueryFuture[T] = queryProcessor.asFuture

  sealed case class CommandProcessor[T](whom: Either[ActorRef, ActorSelection], msg: Command[T])(implicit tag: ClassTag[T], val akkaTimeout: Timeout = 5 seconds) {

    private def path = whom fold(r => r.path, s => s.anchorPath)

    def withTimeout(akkaTimeout: Timeout): CommandProcessor[T] = {
      CommandProcessor[T](whom, msg)(tag, akkaTimeout)
    }

    def map[R](f: T => R)(implicit executor: ExecutionContext): Future[R] = {
      whom fold(r => r ? msg, s => s ? msg) map {
        case CommandResult(Right(x: T)) => f(x)
        case CommandResult(Left(e: ResponseError)) => throw e
        case z => throw new IllegalArgumentException(s"$path ? $msg replied with unexpected $z")
      }
    }

    def flatMap[R](f: T => Future[R])(implicit executor: ExecutionContext): Future[R] = {
      whom fold(r => r ? msg, s => s ? msg) flatMap {
        case CommandResult(Right(x: T)) => f(x)
        case CommandResult(Left(e: ResponseError)) => Future.failed(e)
        case z => Future.failed(new IllegalArgumentException(s"$path ? $msg replied with unexpected $z"))
      }
    }
  }

}
