package io.digitalmagic.akka.dsl

import akka.actor.{ActorRef, ActorSelection}
import akka.pattern.ask
import akka.util.Timeout
import io.digitalmagic.akka.dsl.API._

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.{implicitConversions, postfixOps}
import scala.reflect.ClassTag
import scala.util.Either

object DSL {

  implicit class PimpedActorRef(val actorRef: ActorRef) {
    def query[T](msg: Query[T])(implicit tag: ClassTag[T]) = QueryProcessor[T](Left(actorRef), msg)

    def command[T](msg: Command[T])(implicit tag: ClassTag[T]) = CommandProcessor[T](Left(actorRef), msg)
  }

  implicit class PimpedActorSelection(val actorSelection: ActorSelection) {
    def query[T](msg: Query[T])(implicit tag: ClassTag[T]) = QueryProcessor[T](Right(actorSelection), msg)

    def command[T](msg: Command[T])(implicit tag: ClassTag[T]) = CommandProcessor[T](Right(actorSelection), msg)
  }

  sealed case class QueryProcessor[T](whom: Either[ActorRef, ActorSelection], msg: Query[T])(implicit tag: ClassTag[T]) {

    implicit val akkaTimeout: Timeout = 5 seconds

    private def path = whom fold(r => r.path, s => s.anchorPath)

    def map[R](f: T => R)(implicit executor: ExecutionContext): Future[R] = {
      whom fold(r => r ? msg, s => s ? msg) map {
        case QueryResult(Right(x: T)) => f(x)
        case QueryResult(Left(e: ResponseError)) => throw e
        case z => throw new IllegalArgumentException(s"$path ? $msg replied with unexpected $z")
      }
    }

    def withFilter[R](p: T => Boolean): Future[R] = Future.failed(new NotImplementedError)

    def flatMap[R](f: T => Future[R])(implicit executor: ExecutionContext): Future[R] = {
      whom fold(r => r ? msg, s => s ? msg) flatMap {
        case QueryResult(Right(x: T)) => f(x)
        case QueryResult(Left(e: ResponseError)) => Future.failed(e)
        case z => Future.failed(new IllegalArgumentException(s"$path ? $msg replied with unexpected $z"))
      }
    }
  }

  sealed case class CommandProcessor[T](whom: Either[ActorRef, ActorSelection], msg: Command[T])(implicit tag: ClassTag[T]) {

    implicit val akkaTimeout: Timeout = 5 seconds

    private def path = whom fold(r => r.path, s => s.anchorPath)

    def map[R](f: T => R)(implicit executor: ExecutionContext): Future[R] = {
      whom fold(r => r ? msg, s => s ? msg) map {
        case CommandResult(Right(x: T)) => f(x)
        case CommandResult(Left(e: ResponseError)) => throw e
        case z => throw new IllegalArgumentException(s"$path ? $msg replied with unexpected $z")
      }
    }

    def withFilter[R](p: T => Boolean): Future[R] = Future.failed(new NotImplementedError)

    def flatMap[R](f: T => Future[R])(implicit executor: ExecutionContext): Future[R] = {
      whom fold(r => r ? msg, s => s ? msg) flatMap {
        case CommandResult(Right(x: T)) => f(x)
        case CommandResult(Left(e: ResponseError)) => Future.failed(e)
        case z => Future.failed(new IllegalArgumentException(s"$path ? $msg replied with unexpected $z"))
      }
    }
  }

}
