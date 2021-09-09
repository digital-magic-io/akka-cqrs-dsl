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
  implicit case object myIndexApi extends UniqueIndexApi.Base[String, String]
}

object EntityActor {
  def props: Props = Props(new EntityActor())
}

class EntityActor extends Actor {
  import UniqueIndexActorSpec.myIndexApi._

  override def receive: Receive = {
    case q: IsIndexNeeded if q.key.endsWith("-no") =>
      sender() ! q.success(UniqueIndexApi.IsIndexNeededResponse.No)
    case q: IsIndexNeeded if q.key.endsWith("-unknown") =>
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

  implicit val index = new ActorBasedUniqueIndex[myIndexApi.type](system.actorSelection("user/entity"), system.actorSelection("system/sharding/index"))
  private val clusterSharding = ClusterSharding(system)
  private val indexActor = TestUniqueIndexActorDef(myIndexApi, "index").start(clusterSharding, ClusterShardingSettings(system))

  "index actor" must {
    import myIndexApi._
    import UniqueIndexApi._

    "support index acquisition" in {
      val entityId = "entityId-1"
      val key = "key-1"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId, key)
        _ <- indexActor command CommitAcquisition(entityId, key)
      } yield ()

      Await.result(res, 3 seconds)

      Await.result(indexActor query GetEntityId(key) map identity, 3 seconds) shouldBe Some(entityId)
    }

    "support index acquisition rollback" in {
      val entityId = "entityId-2"
      val key = "key-2"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId, key)
        _ <- indexActor command RollbackAcquisition(entityId, key)
      } yield ()

      Await.result(res, 3 seconds)

      Await.result(indexActor query myIndexApi.GetEntityId(key) map identity, 3 seconds) shouldBe None
    }

    "not allow duplicate index acquisition" in {
      val entityId = "entityId-3"
      val key = "key-3"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId, key)
        _ <- indexActor command CommitAcquisition(entityId, key)
      } yield ()

      Await.result(res, 3 seconds)

      an [DuplicateIndex] should be thrownBy Await.result(indexActor command StartAcquisition(entityId, key) map identity, 3 seconds)
    }

    "not allow duplicate index acquisition with different entityId" in {
      val entityId1 = "entityId-4-1"
      val entityId2 = "entityId-4-2"
      val key = "key-4"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId1, key)
        _ <- indexActor command CommitAcquisition(entityId1, key)
      } yield ()

      Await.result(res, 3 seconds)

      an [DuplicateIndex] should be thrownBy Await.result(indexActor command StartAcquisition(entityId2, key) map identity, 3 seconds)
    }

    "recover from incomplete acquisition on query if key is needed by entity" in {
      val entityId = "entityId-5"
      val key = "key-5"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId, key)
        entityId <- indexActor query GetEntityId(key)
      } yield entityId

      Await.result(res, 3 seconds) shouldBe Some(entityId)
      Await.result(indexActor query GetEntityId(key) map identity, 3 seconds) shouldBe Some(entityId)
    }

    "recover from incomplete acquisition on query if key is not needed by entity" in {
      val entityId = "entityId-6"
      val key = "key-6-no"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId, key)
        entityId <- indexActor query GetEntityId(key)
      } yield entityId

      Await.result(res, 3 seconds) shouldBe None
      Await.result(indexActor query GetEntityId(key) map identity, 3 seconds) shouldBe None
    }

    "not return entity if it's unknown if key is needed after incomplete acquisition" in {
      val entityId = "entityId-7"
      val key = "key-7-unknown"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId, key)
        entityId <- indexActor query GetEntityId(key)
      } yield entityId

      Await.result(res, 3 seconds) shouldBe None
      Await.result(indexActor query GetEntityId(key) map identity, 3 seconds) shouldBe None
    }

    "recover from incomplete acquisition on next acquisition attempt if key is needed by entity" in {
      val entityId1 = "entityId-8-1"
      val entityId2 = "entityId-8-2"
      val key = "key-8"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId1, key)
        _ <- indexActor command StartAcquisition(entityId2, key)
      } yield ()

      an [DuplicateIndex] should be thrownBy Await.result(res, 3 seconds)
      Await.result(indexActor query GetEntityId(key) map identity, 3 seconds) shouldBe Some(entityId1)
    }

    "recover from incomplete acquisition on next acquisition attempt if key is not needed by entity" in {
      val entityId1 = "entityId-9-1"
      val entityId2 = "entityId-9-2"
      val key = "key-9-no"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId1, key)
        _ <- indexActor command StartAcquisition(entityId2, key)
        _ <- indexActor command CommitAcquisition(entityId2, key)
      } yield ()

      Await.result(res, 3 seconds)
      Await.result(indexActor query GetEntityId(key) map identity, 3 seconds) shouldBe Some(entityId2)
    }

    "not allow to acquire key if it's unknown if key is needed after incomplete acquisition" in {
      val entityId1 = "entityId-10-1"
      val entityId2 = "entityId-10-2"
      val key = "key-10-unknown"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId1, key)
        _ <- indexActor command StartAcquisition(entityId2, key)
      } yield ()

      an [DuplicateIndex] should be thrownBy Await.result(res, 3 seconds)
      Await.result(indexActor query GetEntityId(key) map identity, 3 seconds) shouldBe None
    }

    "support index release" in {
      val entityId = "entityId-11"
      val key = "key-11-unknown"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId, key)
        _ <- indexActor command CommitAcquisition(entityId, key)
        _ <- indexActor command StartRelease(entityId, key)
        _ <- indexActor command CommitRelease(entityId, key)
      } yield ()

      Await.result(res, 3 seconds)

      Await.result(indexActor query myIndexApi.GetEntityId(key) map identity, 3 seconds) shouldBe None
    }

    "support index release rollback" in {
      val entityId = "entityId-12"
      val key = "key-12-unknown"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId, key)
        _ <- indexActor command CommitAcquisition(entityId, key)
        _ <- indexActor command StartRelease(entityId, key)
        _ <- indexActor command RollbackRelease(entityId, key)
      } yield ()

      Await.result(res, 3 seconds)

      Await.result(indexActor query myIndexApi.GetEntityId(key) map identity, 3 seconds) shouldBe Some(entityId)
    }

    "not allow double index release" in {
      val entityId = "entityId-13"
      val key = "key-13"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId, key)
        _ <- indexActor command CommitAcquisition(entityId, key)
        _ <- indexActor command StartRelease(entityId, key)
        _ <- indexActor command CommitRelease(entityId, key)
        _ <- indexActor command StartRelease(entityId, key)
      } yield ()

      an [IndexIsFree] should be thrownBy Await.result(res, 3 seconds)
    }

    "not allow release with different entityId" in {
      val entityId1 = "entityId-14-1"
      val entityId2 = "entityId-14-2"
      val key = "key-14"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId1, key)
        _ <- indexActor command CommitAcquisition(entityId1, key)
        _ <- indexActor command StartRelease(entityId2, key)
      } yield ()

      an [EntityIdMismatch] should be thrownBy Await.result(res, 3 seconds)
    }

    "recover from incomplete release on query if key is needed by entity" in {
      val entityId = "entityId-15"
      val key = "key-15"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId, key)
        _ <- indexActor command CommitAcquisition(entityId, key)
        _ <- indexActor command StartRelease(entityId, key)
        entityId <- indexActor query GetEntityId(key)
      } yield entityId

      Await.result(res, 3 seconds) shouldBe Some(entityId)
      Await.result(indexActor query GetEntityId(key) map identity, 3 seconds) shouldBe Some(entityId)
    }

    "recover from incomplete release on query if key is not needed by entity" in {
      val entityId = "entityId-16"
      val key = "key-16-no"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId, key)
        _ <- indexActor command CommitAcquisition(entityId, key)
        _ <- indexActor command StartRelease(entityId, key)
        entityId <- indexActor query GetEntityId(key)
      } yield entityId

      Await.result(res, 3 seconds) shouldBe None
      Await.result(indexActor query GetEntityId(key) map identity, 3 seconds) shouldBe None
    }

    "not return entity if it's unknown if key is needed after incomplete release" in {
      val entityId = "entityId-17"
      val key = "key-17-unknown"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId, key)
        _ <- indexActor command CommitAcquisition(entityId, key)
        _ <- indexActor command StartRelease(entityId, key)
        entityId <- indexActor query GetEntityId(key)
      } yield entityId

      Await.result(res, 3 seconds) shouldBe None
      Await.result(indexActor query GetEntityId(key) map identity, 3 seconds) shouldBe None
    }

    "recover from incomplete release on next acquisition attempt if key is needed by entity" in {
      val entityId1 = "entityId-18-1"
      val entityId2 = "entityId-18-2"
      val key = "key-18"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId1, key)
        _ <- indexActor command CommitAcquisition(entityId1, key)
        _ <- indexActor command StartRelease(entityId1, key)
        _ <- indexActor command StartAcquisition(entityId2, key)
      } yield ()

      an [DuplicateIndex] should be thrownBy Await.result(res, 3 seconds)
      Await.result(indexActor query GetEntityId(key) map identity, 3 seconds) shouldBe Some(entityId1)
    }

    "recover from incomplete release on next acquisition attempt if key is not needed by entity" in {
      val entityId1 = "entityId-19-1"
      val entityId2 = "entityId-19-2"
      val key = "key-19-no"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId1, key)
        _ <- indexActor command CommitAcquisition(entityId1, key)
        _ <- indexActor command StartRelease(entityId1, key)
        _ <- indexActor command StartAcquisition(entityId2, key)
        _ <- indexActor command CommitAcquisition(entityId2, key)
      } yield ()

      Await.result(res, 3 seconds)
      Await.result(indexActor query GetEntityId(key) map identity, 3 seconds) shouldBe Some(entityId2)
    }

    "not allow to acquire key if it's unknown if key is needed after incomplete release" in {
      val entityId1 = "entityId-20-1"
      val entityId2 = "entityId-20-2"
      val key = "key-20-unknown"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId1, key)
        _ <- indexActor command CommitAcquisition(entityId1, key)
        _ <- indexActor command StartRelease(entityId1, key)
        _ <- indexActor command StartAcquisition(entityId2, key)
      } yield ()

      an [DuplicateIndex] should be thrownBy Await.result(res, 3 seconds)
      Await.result(indexActor query GetEntityId(key) map identity, 3 seconds) shouldBe None
    }

    "not allow acquisition commit with different entityId" in {
      val entityId1 = "entityId-21-1"
      val entityId2 = "entityId-21-2"
      val key = "key-21"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId1, key)
        _ <- indexActor command CommitAcquisition(entityId2, key)
      } yield ()

      an [EntityIdMismatch] should be thrownBy Await.result(res, 3 seconds)
    }

    "not allow acquisition rollback with different entityId" in {
      val entityId1 = "entityId-22-1"
      val entityId2 = "entityId-22-2"
      val key = "key-22"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId1, key)
        _ <- indexActor command RollbackAcquisition(entityId2, key)
      } yield ()

      an [EntityIdMismatch] should be thrownBy Await.result(res, 3 seconds)
    }

    "not allow release commit with different entityId" in {
      val entityId1 = "entityId-23-1"
      val entityId2 = "entityId-23-2"
      val key = "key-23"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId1, key)
        _ <- indexActor command CommitAcquisition(entityId1, key)
        _ <- indexActor command StartRelease(entityId1, key)
        _ <- indexActor command CommitRelease(entityId2, key)
      } yield ()

      an [EntityIdMismatch] should be thrownBy Await.result(res, 3 seconds)
    }

    "not allow release rollback with different entityId" in {
      val entityId1 = "entityId-24-1"
      val entityId2 = "entityId-24-2"
      val key = "key-24"

      val res = for {
        _ <- indexActor command StartAcquisition(entityId1, key)
        _ <- indexActor command CommitAcquisition(entityId1, key)
        _ <- indexActor command StartRelease(entityId1, key)
        _ <- indexActor command RollbackRelease(entityId2, key)
      } yield ()

      an [EntityIdMismatch] should be thrownBy Await.result(res, 3 seconds)
    }
  }
}
