package io.digitalmagic.akka.dsl

import java.util.concurrent.TimeoutException

import akka.actor._
import akka.cluster.Cluster
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.fixes.ClusterShardingExts.implicits._
import akka.pattern._
import akka.testkit.{ImplicitSender, TestKit}
import akka.util.Timeout
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import io.digitalmagic.akka.dsl.API._
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

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

  def this() = this(ActorSystem("api-test", ConfigFactory.load("akka-test.conf")
    .withValue("akka.actor.provider", ConfigValueFactory.fromAnyRef("akka.cluster.ClusterActorRefProvider"))
  ))
  Cluster(system).join(Address("tcp", system.name))

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

    "support program interpretation" in {
      implicit val actor1Api = Actor1.interpreter(system.actorSelection("/user/actor1"))
      implicit val actor2Api = Actor2.interpreter(system.actorSelection("/user/actor2"))

      val actor1 = system.actorOf(Actor1.props, "actor1")
      val actor2 = system.actorOf(Actor2.props, "actor2")
      val adder = system.actorOf(Adder.props, "adder")

      val future = for {
        _     <- actor1 command Actor1.SetValue(5)
        _     <- actor2 command Actor2.SetValue(10)
        value <- adder command Adder.QueryAndAdd
      } yield value

      Await.result(future, 3 seconds) shouldBe 15
    }

    "support unique indexes" in {
      import IndexExample._

      implicit val index1 = new ActorBasedUniqueIndex[index1Api.type] {
        override def entityActor: ActorSelection = system.actorSelection("system/sharding/example")
        override def indexActor: ActorSelection = system.actorSelection("system/sharding/index1")
      }

      implicit val index2 = new ActorBasedUniqueIndex[index2Api.type] {
        override def entityActor: ActorSelection = system.actorSelection("system/sharding/example")
        override def indexActor: ActorSelection = system.actorSelection("system/sharding/index2")
      }

      val clusterSharding = ClusterSharding(system)
      val clusterShardingSettings = ClusterShardingSettings(system)
      val shardAllocationStrategy = clusterSharding.defaultShardAllocationStrategy(clusterShardingSettings)

      UniqueIndexActorDef(index1Api, "index1").start(clusterSharding, clusterShardingSettings)
      UniqueIndexActorDef(index2Api, "index2").start(clusterSharding, clusterShardingSettings)

      clusterSharding.startProperly(
        typeName = "example",
        entityIdToEntityProps = IndexExampleActor.props,
        settings = clusterShardingSettings,
        extractEntityId = IndexExampleActor.extractEntityId,
        extractShardId = IndexExampleActor.extractShardId,
        allocationStrategy = shardAllocationStrategy,
        handOffStopMessage = EventSourcedActorWithInterpreter.Stop
      )

      {
        val resp = index1.entityActor command IndexExample.AcquireCommand("e1", false) map identity
        Await.result(resp, 3 seconds) shouldBe (())
      }

      val entityId = {
        val resp = index1.indexActor query index1Api.GetEntityId("abc") map identity
        val optionEntityId = Await.result(resp, 3 seconds)
        optionEntityId shouldBe Some("e1")
        optionEntityId.get
      }

      {
        val resp = index1.entityActor command IndexExample.ReleaseCommand(entityId) map identity
        Await.result(resp, 3 seconds) shouldBe (())
      }

      Await.result(index1.indexActor query index1Api.GetEntityId("abc") map identity, 3 seconds) shouldBe None
     }
  }
}
