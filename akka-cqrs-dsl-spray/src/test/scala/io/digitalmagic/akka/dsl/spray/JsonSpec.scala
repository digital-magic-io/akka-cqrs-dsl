package io.digitalmagic.akka.dsl.spray

import io.digitalmagic.akka.dsl.EventSourcedActorWithInterpreter.EventSourcedActorState
import io.digitalmagic.akka.dsl._
import org.scalatest.{Matchers, WordSpecLike}
import _root_.spray.json._

object JsonSpec {
  import DefaultJsonProtocol._

  implicit object api1 extends UniqueIndexApi.Base[String, String]
  implicit object api2 extends UniqueIndexApi.Base[Int, Int]

  trait MyEvent extends Event
  case class MyState(n: Int) extends PersistentState {
    override type EventType = MyEvent
  }

  implicit val myStateFormat: JsonFormat[MyState] = new JsonFormat[MyState] {
    override def read(json: JsValue): MyState = MyState(json.asJsObject().fields("n").convertTo[Int])
    override def write(obj: MyState): JsValue = JsObject("n" -> obj.n.toJson)
  }

  implicit val stateFormat: JsonFormat[PersistentState] = new JsonFormat[PersistentState] {
    override def read(json: JsValue): PersistentState = {
      val fields = json.asJsObject.fields
      fields("type").convertTo[String] match {
        case "MyState" => fields("key").convertTo[MyState]
        case other     => deserializationError(s"unknown state type $other")
      }
    }
    override def write(obj: PersistentState): JsValue = {
      val (stateType, value) = obj match {
        case s: MyState => ("MyState", s.toJson)
        case s => serializationError(s"unknown state class: ${s.getClass}")
      }
      JsObject(
        "type" -> stateType.toJson,
        "key" -> value
      )
    }
  }
}

class JsonSpec extends WordSpecLike with Matchers {
  import Json._
  import JsonSpec._

  "spray-json for ClientEvent" must {
    "support serializing AcquisitionStartedClientEvent" in {
      {
        val event = api1.AcquisitionStartedClientEvent("test")
        val serialized = clientEventFormat.write(event)
        val deserialized = clientEventFormat.read(serialized)
        deserialized shouldBe event
      }

      {
        val event = api2.AcquisitionStartedClientEvent(42)
        val serialized = clientEventFormat.write(event)
        val deserialized = clientEventFormat.read(serialized)
        deserialized shouldBe event
      }
    }

    "support serializing AcquisitionCompletedClientEvent" in {
      {
        val event = api1.AcquisitionCompletedClientEvent("test")
        val serialized = clientEventFormat.write(event)
        val deserialized = clientEventFormat.read(serialized)
        deserialized shouldBe event
      }

      {
        val event = api2.AcquisitionCompletedClientEvent(42)
        val serialized = clientEventFormat.write(event)
        val deserialized = clientEventFormat.read(serialized)
        deserialized shouldBe event
      }
    }

    "support serializing AcquisitionAbortedClientEvent" in {
      {
        val event = api1.AcquisitionAbortedClientEvent("test")
        val serialized = clientEventFormat.write(event)
        val deserialized = clientEventFormat.read(serialized)
        deserialized shouldBe event
      }

      {
        val event = api2.AcquisitionAbortedClientEvent(42)
        val serialized = clientEventFormat.write(event)
        val deserialized = clientEventFormat.read(serialized)
        deserialized shouldBe event
      }
    }

    "support serializing ReleaseStartedClientEvent" in {
      {
        val event = api1.ReleaseStartedClientEvent("test")
        val serialized = clientEventFormat.write(event)
        val deserialized = clientEventFormat.read(serialized)
        deserialized shouldBe event
      }

      {
        val event = api2.ReleaseStartedClientEvent(42)
        val serialized = clientEventFormat.write(event)
        val deserialized = clientEventFormat.read(serialized)
        deserialized shouldBe event
      }
    }

    "support serializing ReleaseCompletedClientEvent" in {
      {
        val event = api1.ReleaseCompletedClientEvent("test")
        val serialized = clientEventFormat.write(event)
        val deserialized = clientEventFormat.read(serialized)
        deserialized shouldBe event
      }

      {
        val event = api2.ReleaseCompletedClientEvent(42)
        val serialized = clientEventFormat.write(event)
        val deserialized = clientEventFormat.read(serialized)
        deserialized shouldBe event
      }
    }

    "support serializing ReleaseAbortedClientEvent" in {
      {
        val event = api1.ReleaseAbortedClientEvent("test")
        val serialized = clientEventFormat.write(event)
        val deserialized = clientEventFormat.read(serialized)
        deserialized shouldBe event
      }

      {
        val event = api2.ReleaseAbortedClientEvent(42)
        val serialized = clientEventFormat.write(event)
        val deserialized = clientEventFormat.read(serialized)
        deserialized shouldBe event
      }
    }
  }

  "spray-json for EventSourcedActorState" must {
    "support serializing EventSourcedActorState" in {
      val state = EventSourcedActorState(MyState(1), ClientIndexesStateMap(Map(
        api1 -> api1.ClientIndexesState(Map("abc" -> api1.AcquisitionPendingClientState(), "def" -> api1.ReleasePendingClientState(), "ghi" -> api1.AcquiredClientState())),
        api2 -> api2.ClientIndexesState(Map(1 -> api2.AcquisitionPendingClientState(), 2 -> api2.ReleasePendingClientState(), 3 -> api2.AcquiredClientState()))
      )))
      val serialized = state.toJson
      val deserialized = serialized.convertTo[EventSourcedActorState[MyState]]
      deserialized shouldBe state
    }
  }

  "spray-json for Error" must {
    "support serializing DuplicateIndex" in {
      {
        val error = api1.DuplicateIndex("entityId", "key")
        val serialized = errorFormat.write(error)
        val deserialized = errorFormat.read(serialized)
        deserialized shouldBe error
      }

      {
        val error = api2.DuplicateIndex(41, 42)
        val serialized = errorFormat.write(error)
        val deserialized = errorFormat.read(serialized)
        deserialized shouldBe error
      }
    }

    "support serializing IndexIsFree" in {
      {
        val error = api1.IndexIsFree("entityId", "key")
        val serialized = errorFormat.write(error)
        val deserialized = errorFormat.read(serialized)
        deserialized shouldBe error
      }

      {
        val error = api2.IndexIsFree(41, 42)
        val serialized = errorFormat.write(error)
        val deserialized = errorFormat.read(serialized)
        deserialized shouldBe error
      }
    }

    "support serializing IndexIsAcquired" in {
      {
        val error = api1.IndexIsAcquired("entityId", "key")
        val serialized = errorFormat.write(error)
        val deserialized = errorFormat.read(serialized)
        deserialized shouldBe error
      }

      {
        val error = api2.IndexIsAcquired(41, 42)
        val serialized = errorFormat.write(error)
        val deserialized = errorFormat.read(serialized)
        deserialized shouldBe error
      }
    }

    "support serializing EntityIdMismatch" in {
      {
        val error = api1.EntityIdMismatch("occupyingEntityId", "requestedEntityId", "key")
        val serialized = errorFormat.write(error)
        val deserialized = errorFormat.read(serialized)
        deserialized shouldBe error
      }

      {
        val error = api2.EntityIdMismatch(41, 42, 43)
        val serialized = errorFormat.write(error)
        val deserialized = errorFormat.read(serialized)
        deserialized shouldBe error
      }
    }

  }

  "spray-json for ServerEvent" must {
    "support serializing AcquisitionStartedServerEvent" in {
      {
        val event = api1.AcquisitionStartedServerEvent("test")
        val serialized = serverEventFormat.write(event)
        val deserialized = serverEventFormat.read(serialized)
        deserialized shouldBe event
      }

      {
        val event = api2.AcquisitionStartedServerEvent(42)
        val serialized = serverEventFormat.write(event)
        val deserialized = serverEventFormat.read(serialized)
        deserialized shouldBe event
      }
    }

    "support serializing AcquisitionCompletedServerEvent" in {
      {
        val event = api1.AcquisitionCompletedServerEvent()
        val serialized = serverEventFormat.write(event)
        val deserialized = serverEventFormat.read(serialized)
        deserialized shouldBe event
      }

      {
        val event = api2.AcquisitionCompletedServerEvent()
        val serialized = serverEventFormat.write(event)
        val deserialized = serverEventFormat.read(serialized)
        deserialized shouldBe event
      }
    }

    "support serializing ReleaseStartedServerEvent" in {
      {
        val event = api1.ReleaseStartedServerEvent()
        val serialized = serverEventFormat.write(event)
        val deserialized = serverEventFormat.read(serialized)
        deserialized shouldBe event
      }

      {
        val event = api2.ReleaseStartedServerEvent()
        val serialized = serverEventFormat.write(event)
        val deserialized = serverEventFormat.read(serialized)
        deserialized shouldBe event
      }
    }

    "support serializing ReleaseCompletedServerEvent" in {
      {
        val event = api1.ReleaseCompletedServerEvent()
        val serialized = serverEventFormat.write(event)
        val deserialized = serverEventFormat.read(serialized)
        deserialized shouldBe event
      }

      {
        val event = api2.ReleaseCompletedServerEvent()
        val serialized = serverEventFormat.write(event)
        val deserialized = serverEventFormat.read(serialized)
        deserialized shouldBe event
      }
    }
  }

  "spray-json for ServerState" must {
    "support serializing FreeServerState" in {
      {
        val state = api1.FreeServerState()
        val serialized = serverStateFormat.write(state)
        val deserialized = serverStateFormat.read(serialized)
        deserialized shouldBe state
      }

      {
        val state = api2.FreeServerState()
        val serialized = serverStateFormat.write(state)
        val deserialized = serverStateFormat.read(serialized)
        deserialized shouldBe state
      }
    }

    "support serializing UnconfirmedServerState" in {
      {
        val state = api1.UnconfirmedServerState("entityId")
        val serialized = serverStateFormat.write(state)
        val deserialized = serverStateFormat.read(serialized)
        deserialized shouldBe state
      }

      {
        val state = api2.UnconfirmedServerState(42)
        val serialized = serverStateFormat.write(state)
        val deserialized = serverStateFormat.read(serialized)
        deserialized shouldBe state
      }
    }

    "support serializing AcquiredServerState" in {
      {
        val state = api1.AcquiredServerState("entityId")
        val serialized = serverStateFormat.write(state)
        val deserialized = serverStateFormat.read(serialized)
        deserialized shouldBe state
      }

      {
        val state = api2.AcquiredServerState(42)
        val serialized = serverStateFormat.write(state)
        val deserialized = serverStateFormat.read(serialized)
        deserialized shouldBe state
      }
    }
  }

  "spray-json for UniqueIndexRequest" must {
    "support serializing GetEntityId" in {
      {
        val request = api1.GetEntityId("key")
        val serialized = uniqueIndexRequestFormat.write(request)
        val deserialized = uniqueIndexRequestFormat.read(serialized)
        deserialized shouldBe request
      }

      {
        val request = api2.GetEntityId(42)
        val serialized = uniqueIndexRequestFormat.write(request)
        val deserialized = uniqueIndexRequestFormat.read(serialized)
        deserialized shouldBe request
      }
    }

    "support serializing StartAcquisition" in {
      {
        val request = api1.StartAcquisition("entityId", "key")
        val serialized = uniqueIndexRequestFormat.write(request)
        val deserialized = uniqueIndexRequestFormat.read(serialized)
        deserialized shouldBe request
      }

      {
        val request = api2.StartAcquisition(41, 42)
        val serialized = uniqueIndexRequestFormat.write(request)
        val deserialized = uniqueIndexRequestFormat.read(serialized)
        deserialized shouldBe request
      }
    }

    "support serializing CommitAcquisition" in {
      {
        val request = api1.CommitAcquisition("entityId", "key")
        val serialized = uniqueIndexRequestFormat.write(request)
        val deserialized = uniqueIndexRequestFormat.read(serialized)
        deserialized shouldBe request
      }

      {
        val request = api2.CommitAcquisition(41, 42)
        val serialized = uniqueIndexRequestFormat.write(request)
        val deserialized = uniqueIndexRequestFormat.read(serialized)
        deserialized shouldBe request
      }
    }

    "support serializing RollbackAcquisition" in {
      {
        val request = api1.RollbackAcquisition("entityId", "key")
        val serialized = uniqueIndexRequestFormat.write(request)
        val deserialized = uniqueIndexRequestFormat.read(serialized)
        deserialized shouldBe request
      }

      {
        val request = api2.RollbackAcquisition(41, 42)
        val serialized = uniqueIndexRequestFormat.write(request)
        val deserialized = uniqueIndexRequestFormat.read(serialized)
        deserialized shouldBe request
      }
    }

    "support serializing StartRelease" in {
      {
        val request = api1.StartRelease("entityId", "key")
        val serialized = uniqueIndexRequestFormat.write(request)
        val deserialized = uniqueIndexRequestFormat.read(serialized)
        deserialized shouldBe request
      }

      {
        val request = api2.StartRelease(41, 42)
        val serialized = uniqueIndexRequestFormat.write(request)
        val deserialized = uniqueIndexRequestFormat.read(serialized)
        deserialized shouldBe request
      }
    }

    "support serializing CommitRelease" in {
      {
        val request = api1.CommitRelease("entityId", "key")
        val serialized = uniqueIndexRequestFormat.write(request)
        val deserialized = uniqueIndexRequestFormat.read(serialized)
        deserialized shouldBe request
      }

      {
        val request = api2.CommitRelease(41, 42)
        val serialized = uniqueIndexRequestFormat.write(request)
        val deserialized = uniqueIndexRequestFormat.read(serialized)
        deserialized shouldBe request
      }
    }

    "support serializing RollbackRelease" in {
      {
        val request = api1.RollbackRelease("entityId", "key")
        val serialized = uniqueIndexRequestFormat.write(request)
        val deserialized = uniqueIndexRequestFormat.read(serialized)
        deserialized shouldBe request
      }

      {
        val request = api2.RollbackRelease(41, 42)
        val serialized = uniqueIndexRequestFormat.write(request)
        val deserialized = uniqueIndexRequestFormat.read(serialized)
        deserialized shouldBe request
      }
    }

  }
}