package io.digitalmagic.akka.dsl.javaserialization

import java.io.{ByteArrayInputStream, ByteArrayOutputStream, ObjectInputStream, ObjectOutputStream}

import io.digitalmagic.akka.dsl.EventSourcedActorWithInterpreter.EventSourcedActorState
import io.digitalmagic.akka.dsl.{ClientIndexesStateMap, Event, PersistentState, UniqueIndexApi}
import org.scalatest.{Matchers, WordSpecLike}

object JavaSerializationSpec {
  case object test
  @SerialVersionUID(1)
  implicit case object api1 extends UniqueIndexApi.Base[String, String]
  @SerialVersionUID(1)
  implicit case object api2 extends UniqueIndexApi.Base[Int, Int]

  trait MyEvent extends Event
  case class MyState(n: Int) extends PersistentState {
    override type EventType = MyEvent
  }
}

class JavaSerializationSpec extends WordSpecLike with Matchers {
  import JavaSerializationSpec._

  def write(obj: Any): Array[Byte] = {
    val baos = new ByteArrayOutputStream()
    val oos = new ObjectOutputStream(baos)
    oos.writeObject(obj)
    oos.close()
    baos.close()
    baos.toByteArray
  }

  def read[T](arr: Array[Byte]): T = {
    val bais = new ByteArrayInputStream(arr)
    val ois = new ObjectInputStream(bais)
    val deserialized = ois.readObject()
    ois.close()
    bais.close()
    deserialized.asInstanceOf[T]
  }

  "java serialization for ClientEvent" must {
    "support serializing AcquisitionStartedClientEvent" in {
      {
        val event = api1.AcquisitionStartedClientEvent("test")
        val serialized = write(event)
        val deserialized = read[api1.AcquisitionStartedClientEvent](serialized)
        deserialized shouldBe event
      }

      {
        val event = api2.AcquisitionStartedClientEvent(42)
        val serialized = write(event)
        val deserialized = read[api2.AcquisitionStartedClientEvent](serialized)
        deserialized shouldBe event
      }
    }

    "support serializing AcquisitionCompletedClientEvent" in {
      {
        val event = api1.AcquisitionCompletedClientEvent("test")
        val serialized = write(event)
        val deserialized = read[api1.AcquisitionCompletedClientEvent](serialized)
        deserialized shouldBe event
      }

      {
        val event = api2.AcquisitionCompletedClientEvent(42)
        val serialized = write(event)
        val deserialized = read[api2.AcquisitionCompletedClientEvent](serialized)
        deserialized shouldBe event
      }
    }

    "support serializing AcquisitionAbortedClientEvent" in {
      {
        val event = api1.AcquisitionAbortedClientEvent("test")
        val serialized = write(event)
        val deserialized = read[api1.AcquisitionAbortedClientEvent](serialized)
        deserialized shouldBe event
      }

      {
        val event = api2.AcquisitionAbortedClientEvent(42)
        val serialized = write(event)
        val deserialized = read[api2.AcquisitionAbortedClientEvent](serialized)
        deserialized shouldBe event
      }
    }

    "support serializing ReleaseStartedClientEvent" in {
      {
        val event = api1.ReleaseStartedClientEvent("test")
        val serialized = write(event)
        val deserialized = read[api1.ReleaseStartedClientEvent](serialized)
        deserialized shouldBe event
      }

      {
        val event = api2.ReleaseStartedClientEvent(42)
        val serialized = write(event)
        val deserialized = read[api2.ReleaseStartedClientEvent](serialized)
        deserialized shouldBe event
      }
    }

    "support serializing ReleaseCompletedClientEvent" in {
      {
        val event = api1.ReleaseCompletedClientEvent("test")
        val serialized = write(event)
        val deserialized = read[api1.ReleaseCompletedClientEvent](serialized)
        deserialized shouldBe event
      }

      {
        val event = api2.ReleaseCompletedClientEvent(42)
        val serialized = write(event)
        val deserialized = read[api2.ReleaseCompletedClientEvent](serialized)
        deserialized shouldBe event
      }
    }

    "support serializing ReleaseAbortedClientEvent" in {
      {
        val event = api1.ReleaseAbortedClientEvent("test")
        val serialized = write(event)
        val deserialized = read[api1.ReleaseAbortedClientEvent](serialized)
        deserialized shouldBe event
      }

      {
        val event = api2.ReleaseAbortedClientEvent(42)
        val serialized = write(event)
        val deserialized = read[api2.ReleaseAbortedClientEvent](serialized)
        deserialized shouldBe event
      }
    }
  }

  "java serialization for EventSourcedActorState" must {
    "support serializing EventSourcedActorState" in {
      val state = EventSourcedActorState(MyState(1), ClientIndexesStateMap(Map(
        api1 -> api1.ClientIndexesState(Map("abc" -> api1.AcquisitionPendingClientState(), "def" -> api1.ReleasePendingClientState(), "ghi" -> api1.AcquiredClientState())),
        api2 -> api2.ClientIndexesState(Map(1 -> api2.AcquisitionPendingClientState(), 2 -> api2.ReleasePendingClientState(), 3 -> api2.AcquiredClientState()))
      )))
      val serialized = write(state)
      val deserialized = read[EventSourcedActorState[MyState]](serialized)
      deserialized shouldBe state
    }
  }

  "java serialization for Error" must {
    "support serializing DuplicateIndex" in {
      {
        val error = api1.DuplicateIndex("key", "value")
        val serialized = write(error)
        val deserialized = read[api1.DuplicateIndex](serialized)
        deserialized shouldBe error
      }

      {
        val error = api2.DuplicateIndex(41, 42)
        val serialized = write(error)
        val deserialized = read[api2.DuplicateIndex](serialized)
        deserialized shouldBe error
      }
    }

    "support serializing IndexIsFree" in {
      {
        val error = api1.IndexIsFree("key", "value")
        val serialized = write(error)
        val deserialized = read[api1.IndexIsFree](serialized)
        deserialized shouldBe error
      }

      {
        val error = api2.IndexIsFree(41, 42)
        val serialized = write(error)
        val deserialized = read[api2.IndexIsFree](serialized)
        deserialized shouldBe error
      }
    }

    "support serializing IndexIsAcquired" in {
      {
        val error = api1.IndexIsAcquired("key", "value")
        val serialized = write(error)
        val deserialized = read[api1.IndexIsAcquired](serialized)
        deserialized shouldBe error
      }

      {
        val error = api2.IndexIsAcquired(41, 42)
        val serialized = write(error)
        val deserialized = read[api2.IndexIsAcquired](serialized)
        deserialized shouldBe error
      }
    }

    "support serializing EntityIdMismatch" in {
      {
        val error = api1.EntityIdMismatch("occupyingKey", "requestedKey", "value")
        val serialized = write(error)
        val deserialized = read[api1.EntityIdMismatch](serialized)
        deserialized shouldBe error
      }

      {
        val error = api2.EntityIdMismatch(41, 42, 43)
        val serialized = write(error)
        val deserialized = read[api2.EntityIdMismatch](serialized)
        deserialized shouldBe error
      }
    }
  }

  "java serialization for ServerEvent" must {
    "support serializing AcquisitionStartedServerEvent" in {
      {
        val event = api1.AcquisitionStartedServerEvent("key")
        val serialized = write(event)
        val deserialized = read[api1.AcquisitionStartedServerEvent](serialized)
        deserialized shouldBe event
      }

      {
        val event = api2.AcquisitionStartedServerEvent(42)
        val serialized = write(event)
        val deserialized = read[api2.AcquisitionStartedServerEvent](serialized)
        deserialized shouldBe event
      }
    }

    "support serializing AcquisitionCompletedServerEvent" in {
      {
        val event = api1.AcquisitionCompletedServerEvent()
        val serialized = write(event)
        val deserialized = read[api1.AcquisitionCompletedServerEvent](serialized)
        deserialized shouldBe event
      }

      {
        val event = api2.AcquisitionCompletedServerEvent()
        val serialized = write(event)
        val deserialized = read[api2.AcquisitionCompletedServerEvent](serialized)
        deserialized shouldBe event
      }
    }

    "support serializing ReleaseStartedServerEvent" in {
      {
        val event = api1.ReleaseStartedServerEvent()
        val serialized = write(event)
        val deserialized = read[api1.ReleaseStartedServerEvent](serialized)
        deserialized shouldBe event
      }

      {
        val event = api2.ReleaseStartedServerEvent()
        val serialized = write(event)
        val deserialized = read[api2.ReleaseStartedServerEvent](serialized)
        deserialized shouldBe event
      }
    }

    "support serializing ReleaseCompletedServerEvent" in {
      {
        val event = api1.ReleaseCompletedServerEvent()
        val serialized = write(event)
        val deserialized = read[api1.ReleaseCompletedServerEvent](serialized)
        deserialized shouldBe event
      }

      {
        val event = api2.ReleaseCompletedServerEvent()
        val serialized = write(event)
        val deserialized = read[api2.ReleaseCompletedServerEvent](serialized)
        deserialized shouldBe event
      }
    }
  }

  "java serialization for ServerState" must {
    "support serializing FreeServerState" in {
      {
        val state = api1.FreeServerState()
        val serialized = write(state)
        val deserialized = read[api1.FreeServerState](serialized)
        deserialized shouldBe state
      }

      {
        val state = api2.FreeServerState()
        val serialized = write(state)
        val deserialized = read[api2.FreeServerState](serialized)
        deserialized shouldBe state
      }
    }

    "support serializing UnconfirmedServerState" in {
      {
        val state = api1.UnconfirmedServerState("key")
        val serialized = write(state)
        val deserialized = read[api1.UnconfirmedServerState](serialized)
        deserialized shouldBe state
      }

      {
        val state = api2.UnconfirmedServerState(42)
        val serialized = write(state)
        val deserialized = read[api2.UnconfirmedServerState](serialized)
        deserialized shouldBe state
      }
    }

    "support serializing AcquiredServerState" in {
      {
        val state = api1.AcquiredServerState("key")
        val serialized = write(state)
        val deserialized = read[api1.AcquiredServerState](serialized)
        deserialized shouldBe state
      }

      {
        val state = api2.AcquiredServerState(42)
        val serialized = write(state)
        val deserialized = read[api2.AcquiredServerState](serialized)
        deserialized shouldBe state
      }
    }
  }

  "java serialization for UniqueIndexRequest" must {
    "support serializing GetEntityId" in {
      {
        val request = api1.GetEntityId("value")
        val serialized = write(request)
        val deserialized = read[api1.GetEntityId](serialized)
        deserialized shouldBe request
      }

      {
        val request = api2.GetEntityId(42)
        val serialized = write(request)
        val deserialized = read[api2.GetEntityId](serialized)
        deserialized shouldBe request
      }
    }

    "support serializing StartAcquisition" in {
      {
        val request = api1.StartAcquisition("key", "value")
        val serialized = write(request)
        val deserialized = read[api1.StartAcquisition](serialized)
        deserialized shouldBe request
      }

      {
        val request = api2.StartAcquisition(41, 42)
        val serialized = write(request)
        val deserialized = read[api2.StartAcquisition](serialized)
        deserialized shouldBe request
      }
    }

    "support serializing CommitAcquisition" in {
      {
        val request = api1.CommitAcquisition("key", "value")
        val serialized = write(request)
        val deserialized = read[api1.CommitAcquisition](serialized)
        deserialized shouldBe request
      }

      {
        val request = api2.CommitAcquisition(41, 42)
        val serialized = write(request)
        val deserialized = read[api2.CommitAcquisition](serialized)
        deserialized shouldBe request
      }
    }

    "support serializing RollbackAcquisition" in {
      {
        val request = api1.RollbackAcquisition("key", "value")
        val serialized = write(request)
        val deserialized = read[api1.RollbackAcquisition](serialized)
        deserialized shouldBe request
      }

      {
        val request = api2.RollbackAcquisition(41, 42)
        val serialized = write(request)
        val deserialized = read[api2.RollbackAcquisition](serialized)
        deserialized shouldBe request
      }
    }

    "support serializing StartRelease" in {
      {
        val request = api1.StartRelease("key", "value")
        val serialized = write(request)
        val deserialized = read[api1.StartRelease](serialized)
        deserialized shouldBe request
      }

      {
        val request = api2.StartRelease(41, 42)
        val serialized = write(request)
        val deserialized = read[api2.StartRelease](serialized)
        deserialized shouldBe request
      }
    }

    "support serializing CommitRelease" in {
      {
        val request = api1.CommitRelease("key", "value")
        val serialized = write(request)
        val deserialized = read[api1.CommitRelease](serialized)
        deserialized shouldBe request
      }

      {
        val request = api2.CommitRelease(41, 42)
        val serialized = write(request)
        val deserialized = read[api2.CommitRelease](serialized)
        deserialized shouldBe request
      }
    }

    "support serializing RollbackRelease" in {
      {
        val request = api1.RollbackRelease("key", "value")
        val serialized = write(request)
        val deserialized = read[api1.RollbackRelease](serialized)
        deserialized shouldBe request
      }

      {
        val request = api2.RollbackRelease(41, 42)
        val serialized = write(request)
        val deserialized = read[api2.RollbackRelease](serialized)
        deserialized shouldBe request
      }
    }
  }
}
