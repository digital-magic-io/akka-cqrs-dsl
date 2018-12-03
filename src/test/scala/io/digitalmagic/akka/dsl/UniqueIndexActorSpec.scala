package io.digitalmagic.akka.dsl

import akka.actor.{Actor, ActorSelection, ActorSystem, Address, Props}
import akka.cluster.Cluster
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.testkit.{ImplicitSender, TestKit}
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}
import io.digitalmagic.akka.dsl.API._
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

import scala.concurrent.Await
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._

object UniqueIndexActorSpec {
  case object myIndexApi extends UniqueIndexApi.Base[String, String]
}

object EntityActor {
  def props: Props = Props(new EntityActor())
}

class EntityActor extends Actor {
  import UniqueIndexActorSpec.myIndexApi._

  override def receive: Receive = {
    case q: IsIndexNeeded if q.value.endsWith("-no") =>
      sender() ! q.success(UniqueIndexApi.IsIndexNeededResponse.No)
    case q: IsIndexNeeded if q.value.endsWith("-unknown") =>
      sender() ! q.success(UniqueIndexApi.IsIndexNeededResponse.Unknown)
    case q: IsIndexNeeded =>
      sender() ! q.success(UniqueIndexApi.IsIndexNeededResponse.Yes)
  }
}

class UniqueIndexActorSpec(system: ActorSystem) extends TestKit(system) with ImplicitSender with WordSpecLike with Matchers with BeforeAndAfterAll {

  import UniqueIndexActorSpec._

  def this() = this(ActorSystem("index-test", ConfigFactory.load("akka-test.conf")
    .withValue("akka.actor.provider", ConfigValueFactory.fromAnyRef("akka.cluster.ClusterActorRefProvider"))
  ))
  Cluster(system).join(Address("tcp", system.name))

  system.actorOf(EntityActor.props, "entity")

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  implicit val index = new ActorBasedUniqueIndex[myIndexApi.type] {
    override def entityActor: ActorSelection = system.actorSelection("user/entity")
    override def indexActor: ActorSelection = system.actorSelection("system/sharding/index")
  }
  private val clusterSharding = ClusterSharding(system)
  private val indexActor = UniqueIndexActorDef(myIndexApi, "index").start(clusterSharding, ClusterShardingSettings(system))

  "index actor" must {
    import myIndexApi._
    import UniqueIndexApi._

    "support index acquisition" in {
      val key = "key-1"
      val value = "value-1"

      val res = for {
        _ <- indexActor command StartAcquisition(key, value)
        _ <- indexActor command CommitAcquisition(key, value)
      } yield ()

      Await.result(res, 3 seconds)

      Await.result(indexActor query GetEntityId(value) map identity, 3 seconds) shouldBe Some(key)
    }

    "support index acquisition rollback" in {
      val key = "key-2"
      val value = "value-2"

      val res = for {
        _ <- indexActor command StartAcquisition(key, value)
        _ <- indexActor command RollbackAcquisition(key, value)
      } yield ()

      Await.result(res, 3 seconds)

      Await.result(indexActor query myIndexApi.GetEntityId(value) map identity, 3 seconds) shouldBe None
    }

    "not allow duplicate index acquisition" in {
      val key = "key-3"
      val value = "value-3"

      val res = for {
        _ <- indexActor command StartAcquisition(key, value)
        _ <- indexActor command CommitAcquisition(key, value)
      } yield ()

      Await.result(res, 3 seconds)

      an [DuplicateIndex] should be thrownBy Await.result(indexActor command StartAcquisition(key, value) map identity, 3 seconds)
    }

    "not allow duplicate index acquisition with different key" in {
      val key1 = "key-4-1"
      val key2 = "key-4-2"
      val value = "value-4"

      val res = for {
        _ <- indexActor command StartAcquisition(key1, value)
        _ <- indexActor command CommitAcquisition(key1, value)
      } yield ()

      Await.result(res, 3 seconds)

      an [DuplicateIndex] should be thrownBy Await.result(indexActor command StartAcquisition(key2, value) map identity, 3 seconds)
    }

    "recover from incomplete acquisition on query if value is needed by entity" in {
      val key = "key-5"
      val value = "value-5"

      val res = for {
        _ <- indexActor command StartAcquisition(key, value)
        entityId <- indexActor query GetEntityId(value)
      } yield entityId

      Await.result(res, 3 seconds) shouldBe Some(key)
      Await.result(indexActor query GetEntityId(value) map identity, 3 seconds) shouldBe Some(key)
    }

    "recover from incomplete acquisition on query if value is not needed by entity" in {
      val key = "key-6"
      val value = "value-6-no"

      val res = for {
        _ <- indexActor command StartAcquisition(key, value)
        entityId <- indexActor query GetEntityId(value)
      } yield entityId

      Await.result(res, 3 seconds) shouldBe None
      Await.result(indexActor query GetEntityId(value) map identity, 3 seconds) shouldBe None
    }

    "not return entity if it's unknown if value is needed after incomplete acquisition" in {
      val key = "key-7"
      val value = "value-7-unknown"

      val res = for {
        _ <- indexActor command StartAcquisition(key, value)
        entityId <- indexActor query GetEntityId(value)
      } yield entityId

      Await.result(res, 3 seconds) shouldBe None
      Await.result(indexActor query GetEntityId(value) map identity, 3 seconds) shouldBe None
    }

    "recover from incomplete acquisition on next acquisition attempt if value is needed by entity" in {
      val key1 = "key-8-1"
      val key2 = "key-8-2"
      val value = "value-8"

      val res = for {
        _ <- indexActor command StartAcquisition(key1, value)
        _ <- indexActor command StartAcquisition(key2, value)
      } yield ()

      an [DuplicateIndex] should be thrownBy Await.result(res, 3 seconds)
      Await.result(indexActor query GetEntityId(value) map identity, 3 seconds) shouldBe Some(key1)
    }

    "recover from incomplete acquisition on next acquisition attempt if value is not needed by entity" in {
      val key1 = "key-9-1"
      val key2 = "key-9-2"
      val value = "value-9-no"

      val res = for {
        _ <- indexActor command StartAcquisition(key1, value)
        _ <- indexActor command StartAcquisition(key2, value)
      } yield ()

      Await.result(res, 3 seconds)
      Await.result(indexActor query GetEntityId(value) map identity, 3 seconds) shouldBe Some(key2)
    }

    "not allow to acquire value if it's unknown if value is needed after incomplete acquisition" in {
      val key1 = "key-10-1"
      val key2 = "key-10-2"
      val value = "value-10-unknown"

      val res = for {
        _ <- indexActor command StartAcquisition(key1, value)
        _ <- indexActor command StartAcquisition(key2, value)
      } yield ()

      an [DuplicateIndex] should be thrownBy Await.result(res, 3 seconds)
      Await.result(indexActor query GetEntityId(value) map identity, 3 seconds) shouldBe None
    }

    "support index release" in {
      val key = "key-11"
      val value = "value-11-unknown"

      val res = for {
        _ <- indexActor command StartAcquisition(key, value)
        _ <- indexActor command CommitAcquisition(key, value)
        _ <- indexActor command StartRelease(key, value)
        _ <- indexActor command CommitRelease(key, value)
      } yield ()

      Await.result(res, 3 seconds)

      Await.result(indexActor query myIndexApi.GetEntityId(value) map identity, 3 seconds) shouldBe None
    }

    "support index release rollback" in {
      val key = "key-12"
      val value = "value-12-unknown"

      val res = for {
        _ <- indexActor command StartAcquisition(key, value)
        _ <- indexActor command CommitAcquisition(key, value)
        _ <- indexActor command StartRelease(key, value)
        _ <- indexActor command RollbackRelease(key, value)
      } yield ()

      Await.result(res, 3 seconds)

      Await.result(indexActor query myIndexApi.GetEntityId(value) map identity, 3 seconds) shouldBe Some(key)
    }

    "not allow double index release" in {
      val key = "key-13"
      val value = "value-13"

      val res = for {
        _ <- indexActor command StartAcquisition(key, value)
        _ <- indexActor command CommitAcquisition(key, value)
        _ <- indexActor command StartRelease(key, value)
        _ <- indexActor command CommitRelease(key, value)
        _ <- indexActor command StartRelease(key, value)
      } yield ()

      an [IndexIsFree] should be thrownBy Await.result(res, 3 seconds)
    }

    "not allow release with different key" in {
      val key1 = "key-14-1"
      val key2 = "key-14-2"
      val value = "value-14"

      val res = for {
        _ <- indexActor command StartAcquisition(key1, value)
        _ <- indexActor command CommitAcquisition(key1, value)
        _ <- indexActor command StartRelease(key2, value)
      } yield ()

      an [EntityIdMismatch] should be thrownBy Await.result(res, 3 seconds)
    }

    "recover from incomplete release on query if value is needed by entity" in {
      val key = "key-15"
      val value = "value-15"

      val res = for {
        _ <- indexActor command StartAcquisition(key, value)
        _ <- indexActor command CommitAcquisition(key, value)
        _ <- indexActor command StartRelease(key, value)
        entityId <- indexActor query GetEntityId(value)
      } yield entityId

      Await.result(res, 3 seconds) shouldBe Some(key)
      Await.result(indexActor query GetEntityId(value) map identity, 3 seconds) shouldBe Some(key)
    }

    "recover from incomplete release on query if value is not needed by entity" in {
      val key = "key-16"
      val value = "value-16-no"

      val res = for {
        _ <- indexActor command StartAcquisition(key, value)
        _ <- indexActor command CommitAcquisition(key, value)
        _ <- indexActor command StartRelease(key, value)
        entityId <- indexActor query GetEntityId(value)
      } yield entityId

      Await.result(res, 3 seconds) shouldBe None
      Await.result(indexActor query GetEntityId(value) map identity, 3 seconds) shouldBe None
    }

    "not return entity if it's unknown if value is needed after incomplete release" in {
      val key = "key-17"
      val value = "value-17-unknown"

      val res = for {
        _ <- indexActor command StartAcquisition(key, value)
        _ <- indexActor command CommitAcquisition(key, value)
        _ <- indexActor command StartRelease(key, value)
        entityId <- indexActor query GetEntityId(value)
      } yield entityId

      Await.result(res, 3 seconds) shouldBe None
      Await.result(indexActor query GetEntityId(value) map identity, 3 seconds) shouldBe None
    }

    "recover from incomplete release on next acquisition attempt if value is needed by entity" in {
      val key1 = "key-18-1"
      val key2 = "key-18-2"
      val value = "value-18"

      val res = for {
        _ <- indexActor command StartAcquisition(key1, value)
        _ <- indexActor command CommitAcquisition(key1, value)
        _ <- indexActor command StartRelease(key1, value)
        _ <- indexActor command StartAcquisition(key2, value)
      } yield ()

      an [DuplicateIndex] should be thrownBy Await.result(res, 3 seconds)
      Await.result(indexActor query GetEntityId(value) map identity, 3 seconds) shouldBe Some(key1)
    }

    "recover from incomplete release on next acquisition attempt if value is not needed by entity" in {
      val key1 = "key-19-1"
      val key2 = "key-19-2"
      val value = "value-19-no"

      val res = for {
        _ <- indexActor command StartAcquisition(key1, value)
        _ <- indexActor command CommitAcquisition(key1, value)
        _ <- indexActor command StartRelease(key1, value)
        _ <- indexActor command StartAcquisition(key2, value)
      } yield ()

      Await.result(res, 3 seconds)
      Await.result(indexActor query GetEntityId(value) map identity, 3 seconds) shouldBe Some(key2)
    }

    "not allow to acquire value if it's unknown if value is needed after incomplete release" in {
      val key1 = "key-20-1"
      val key2 = "key-20-2"
      val value = "value-20-unknown"

      val res = for {
        _ <- indexActor command StartAcquisition(key1, value)
        _ <- indexActor command CommitAcquisition(key1, value)
        _ <- indexActor command StartRelease(key1, value)
        _ <- indexActor command StartAcquisition(key2, value)
      } yield ()

      an [DuplicateIndex] should be thrownBy Await.result(res, 3 seconds)
      Await.result(indexActor query GetEntityId(value) map identity, 3 seconds) shouldBe None
    }

    "not allow acquisition commit with different key" in {
      val key1 = "key-21-1"
      val key2 = "key-21-2"
      val value = "value-21"

      val res = for {
        _ <- indexActor command StartAcquisition(key1, value)
        _ <- indexActor command CommitAcquisition(key2, value)
      } yield ()

      an [EntityIdMismatch] should be thrownBy Await.result(res, 3 seconds)
    }

    "not allow acquisition rollback with different key" in {
      val key1 = "key-22-1"
      val key2 = "key-22-2"
      val value = "value-22"

      val res = for {
        _ <- indexActor command StartAcquisition(key1, value)
        _ <- indexActor command RollbackAcquisition(key2, value)
      } yield ()

      an [EntityIdMismatch] should be thrownBy Await.result(res, 3 seconds)
    }

    "not allow release commit with different key" in {
      val key1 = "key-23-1"
      val key2 = "key-23-2"
      val value = "value-23"

      val res = for {
        _ <- indexActor command StartAcquisition(key1, value)
        _ <- indexActor command CommitAcquisition(key1, value)
        _ <- indexActor command StartRelease(key1, value)
        _ <- indexActor command CommitRelease(key2, value)
      } yield ()

      an [EntityIdMismatch] should be thrownBy Await.result(res, 3 seconds)
    }

    "not allow release rollback with different key" in {
      val key1 = "key-24-1"
      val key2 = "key-24-2"
      val value = "value-24"

      val res = for {
        _ <- indexActor command StartAcquisition(key1, value)
        _ <- indexActor command CommitAcquisition(key1, value)
        _ <- indexActor command StartRelease(key1, value)
        _ <- indexActor command RollbackRelease(key2, value)
      } yield ()

      an [EntityIdMismatch] should be thrownBy Await.result(res, 3 seconds)
    }
  }
}
