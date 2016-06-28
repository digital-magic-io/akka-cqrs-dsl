package io.digitalmagic.akka.dsl

import akka.actor._
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.ConfigFactory
import io.digitalmagic.akka.dsl.API._
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.language.postfixOps

object Domain {
  // queries
  case class QueryValue() extends API.Query[Int]

  // commands
  case class SetValue(newValue: Int) extends API.Command[Int]
  case class BadCommand() extends API.Command[Int]
  case class LongCommand(seconds: Int) extends API.Command[String]

  // response errors
  case object CouldNotExecute extends ResponseError
}

import Domain._

object TestApiService {
  def props() = Props(new TestApiService())
}

class TestApiService() extends Actor {
  override def receive(): Receive = receive(0)

  def receive(value: Int): Receive = {
    case query@QueryValue() => sender() ! query.found(value)
    case command@SetValue(newValue) => sender() ! command.success(newValue); context.become(receive(newValue))
    case command@BadCommand() => sender() ! command.failure(CouldNotExecute)
    case command@LongCommand(sleepFor) => Thread.sleep(sleepFor); sender ! command.success("ok");
  }
}

class DSLSpec(system: ActorSystem) extends TestKit(system) with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {

  def this() = this(ActorSystem("authx-test", ConfigFactory.load("akka-test.conf")))

  lazy val testService = system.actorOf(TestApiService.props())

  override def afterAll() = {
    TestKit.shutdownActorSystem(system)
  }

  "An api query helper" must {

    "respond to queries and commands" in {
      val magic = 15
      val future = for {valueJustSet <- testService command SetValue(magic)
                        _ <- testService command SetValue(magic + valueJustSet)
                        checkIt <- testService query QueryValue()
      } yield {
        checkIt
      }

      Await.result(future, 1 second) shouldBe magic * 2
    }

    "report errors" in {
      val future = testService command BadCommand() map { x =>
        // should not execute
        ???
      } recover {
        case CouldNotExecute =>
        // handle
      }

      Await.result(future, 1 second)
    }

    "timeout properly" in {
      val future = testService command LongCommand(500) withTimeout 100.milliseconds map { x =>
        // should not execute
        ???
      } recover {
        case e: akka.pattern.AskTimeoutException =>
        // handle
      }

      Await.result(future, 1 second)
    }

    "don't timeout" in {
      val future = testService command LongCommand(100) withTimeout 500.milliseconds map { x =>
        // should not execute
        x shouldBe "ok"
      }

      Await.result(future, 1 second)
    }

  }
}
