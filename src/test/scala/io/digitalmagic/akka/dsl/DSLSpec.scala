package io.digitalmagic.akka.dsl

import java.util.concurrent.TimeoutException

import akka.actor._
import akka.pattern._
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import com.typesafe.config.ConfigFactory
import io.digitalmagic.akka.dsl.API._
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

object Domain {
  // queries
  case object QueryValue extends API.Query[Int]
  case class PipedEcho(result: Either[Throwable, Int]) extends API.Query[Int]

  // commands
  case class SetValue(newValue: Int) extends API.Command[Int]
  case object BadCommand extends API.Command[Int]
  case class LongCommand(milliseconds: Int) extends API.Command[String]
  case class PipedSetValue(newValue: Int) extends API.Command[Int]
  case object PipedBadCommand extends API.Command[Int]

  // response errors
  case object CouldNotExecute extends ResponseError
  case object UnexpectedInput extends ResponseError

  case object BadQuery extends Throwable
}

import Domain._

object TestApiService {
  def props() = Props(new TestApiService())
}

class TestApiService() extends Actor {
  override def receive(): Receive = receive(0)

  def receive(value: Int): Receive = {
    case QueryValue => sender() ! QueryValue.found(value)
    case command@SetValue(newValue) => sender() ! command.success(newValue); context.become(receive(newValue))
    case command@LongCommand(sleepFor) => context.system.scheduler.scheduleOnce(sleepFor millis, sender(), command.success("ok"))
    case BadCommand => sender() ! BadCommand.failure(CouldNotExecute)
    case query@PipedEcho(expected) => expected.fold(e => Future.failed(e), v => Future.successful(query.found(v))).pipeTo(sender())
    case command@PipedSetValue(newValue) => self command SetValue(newValue) map command.success pipeTo sender()
    case PipedBadCommand => self command BadCommand map identity pipeTo sender()
  }
}

class DSLSpec(system: ActorSystem) extends TestKit(system) with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("authx-test", ConfigFactory.load("akka-test.conf")))

  private lazy val testService = system.actorOf(TestApiService.props())

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "An api query helper" must {

    "respond to queries and commands on actor ref" in {
      val magic = 15
      val future = for {
        valueJustSet <- testService command SetValue(magic)
        _            <- testService command SetValue(magic + valueJustSet)
        checkIt      <- testService query QueryValue
      } yield {
        checkIt
      }

      Await.result(future, 1 second) shouldBe magic * 2
    }

    "respond to queries and commands on actor selection" in {
      val magic = 15
      val future = for {
        valueJustSet <- system.actorSelection(testService.path) command SetValue(magic)
        _            <- system.actorSelection(testService.path) command SetValue(magic + valueJustSet)
        checkIt      <- system.actorSelection(testService.path) query QueryValue
      } yield {
        checkIt
      }

      Await.result(future, 1 second) shouldBe magic * 2
    }

    "report errors" in {
      val future = testService command BadCommand map { _ =>
        fail("should not execute")
      } recover {
        case CouldNotExecute =>
        // handle
      }

      Await.result(future, 1 second)
    }

    "report errors on actor selection" in {
      val future = system.actorSelection(testService.path) command BadCommand map identity
      an [CouldNotExecute.type] should be thrownBy Await.result(future, 1 second)
    }

    "timeout properly" in {
      val future = testService command LongCommand(500) withTimeout 100.milliseconds map { _ =>
        fail("should not execute")
      } recover {
        case _: akka.pattern.AskTimeoutException =>
        // handle
      }

      Await.result(future, 1 second)
    }

    "timeout properly on actor selection" in {
      val future = system.actorSelection(testService.path) command LongCommand(500) withTimeout 100.milliseconds map identity
      an [AskTimeoutException] should be thrownBy Await.result(future, 1 second)
    }

    "don't timeout" in {
      val future = testService command LongCommand(100) withTimeout 500.milliseconds map { x =>
        x shouldBe "ok"
      }

      Await.result(future, 1 second)
    }

    "don't timeout on actor selection" in {
      val future = system.actorSelection(testService.path) command LongCommand(100) withTimeout 500.milliseconds map identity
      Await.result(future, 1 second) shouldBe "ok"
    }

    "support pipeTo" in {
      locally {
        val result = testService query PipedEcho(Right(7)) map identity
        Await.result(result, 1 second) shouldBe 7
      }

      locally {
        val result = testService command PipedSetValue(42) map identity
        Await.result(result, 1 second) shouldBe 42
      }
    }

    "support failed pipeTo" in {
      locally {
        implicit val t: Timeout = 1 second

        val x = testService ? PipedEcho(Left(BadQuery))
        val y = x recover { case BadQuery => 42 }

        an [BadQuery.type] should be thrownBy Await.result(x, 1 second)
        Await.result(y, 1 second) shouldBe 42
      }

      locally {
        val unsafe = testService query PipedEcho(Left(BadQuery)) map identity
        val safe = unsafe recover { case BadQuery => 42 }

        an [BadQuery.type] should be thrownBy Await.result(unsafe, 1 second)
        Await.result(safe, 1 second) shouldBe 42
      }

      locally {
        val unsafe = testService command  PipedBadCommand map identity
        val safe = unsafe recover { case CouldNotExecute => 42 }

        an [CouldNotExecute.type] should be thrownBy Await.result(unsafe, 1 second)
        Await.result(safe, 1 second) shouldBe 42
      }
    }

    "support global timeout override" in {
      implicit val dslTimeout: Timeout = 300 milliseconds

      locally {
        val res = testService command LongCommand(100) map identity
        Await.result(res, 1 second) shouldBe "ok"
      }

      locally {
        val res = system.actorSelection(testService.path) command LongCommand(100) map identity
        Await.result(res, 1 second) shouldBe "ok"
      }

      locally {
        val res = testService command LongCommand(500) map identity
        an [TimeoutException] should be thrownBy Await.result(res, 1 second)
      }

      locally {
        val res = system.actorSelection(testService.path) command LongCommand(500) map identity
        an [TimeoutException] should be thrownBy Await.result(res, 1 second)
      }
    }
  }
}
