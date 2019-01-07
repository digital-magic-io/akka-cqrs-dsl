package io.digitalmagic.akka.dsl.kryo

import java.time.Instant

import com.esotericsoftware.kryo.io.{Input, Output}
import com.esotericsoftware.kryo.{Kryo, KryoException, Serializer}
import io.digitalmagic.akka.dsl.{ClientIndexesStateMap, UniqueIndexApi}

class ApiSerializer extends Serializer[UniqueIndexApi] {
  override def write(kryo: Kryo, output: Output, obj: UniqueIndexApi): Unit = {
    output.writeString(UniqueIndexApi.getApiIdFor(obj))
  }

  override def read(kryo: Kryo, input: Input, `type`: Class[UniqueIndexApi]): UniqueIndexApi = {
    UniqueIndexApi.getApiById(input.readString())
  }
}

trait Helper {
  def readEntityId(kryo: Kryo, input: Input, api: UniqueIndexApi): api.EntityIdType = kryo.readClassAndObject(input).asInstanceOf[api.EntityIdType]
  def readKey(kryo: Kryo, input: Input, api: UniqueIndexApi): api.KeyType = kryo.readClassAndObject(input).asInstanceOf[api.KeyType]
}

class ClientEventSerializer extends Serializer[UniqueIndexApi#ClientEvent] with Helper {
  val AcquisitionStartedClientEventName = "AcquisitionStartedClientEvent"
  val AcquisitionCompletedClientEventName = "AcquisitionCompletedClientEvent"
  val AcquisitionAbortedClientEventName = "AcquisitionAbortedClientEvent"
  val ReleaseStartedClientEventName = "ReleaseStartedClientEvent"
  val ReleaseCompletedClientEventName = "ReleaseCompletedClientEvent"
  val ReleaseAbortedClientEventName = "ReleaseAbortedClientEvent"

  override def write(kryo: Kryo, output: Output, obj: UniqueIndexApi#ClientEvent): Unit = {
    import obj.Api._

    kryo.writeObject(output, obj.Api)
    obj.reflect match {
      case e: AcquisitionStartedClientEvent =>
        output.writeString(AcquisitionStartedClientEventName)
        kryo.writeClassAndObject(output, e.key)
      case e: AcquisitionCompletedClientEvent =>
        output.writeString(AcquisitionCompletedClientEventName)
        kryo.writeClassAndObject(output, e.key)
      case e: AcquisitionAbortedClientEvent =>
        output.writeString(AcquisitionAbortedClientEventName)
        kryo.writeClassAndObject(output, e.key)
      case e: ReleaseStartedClientEvent =>
        output.writeString(ReleaseStartedClientEventName)
        kryo.writeClassAndObject(output, e.key)
      case e: ReleaseCompletedClientEvent =>
        output.writeString(ReleaseCompletedClientEventName)
        kryo.writeClassAndObject(output, e.key)
      case e: ReleaseAbortedClientEvent =>
        output.writeString(ReleaseAbortedClientEventName)
        kryo.writeClassAndObject(output, e.key)
    }
    kryo.writeObject(output, obj.timestamp)
  }

  override def read(kryo: Kryo, input: Input, `type`: Class[UniqueIndexApi#ClientEvent]): UniqueIndexApi#ClientEvent = {
    val api = kryo.readObject(input, classOf[UniqueIndexApi])
    import api._

    val event = input.readString() match {
      case AcquisitionStartedClientEventName =>
        val key = readKey(kryo, input, api)
        AcquisitionStartedClientEvent(key)
      case AcquisitionCompletedClientEventName =>
        val key = readKey(kryo, input, api)
        AcquisitionCompletedClientEvent(key)
      case AcquisitionAbortedClientEventName =>
        val key = readKey(kryo, input, api)
        AcquisitionAbortedClientEvent(key)
      case ReleaseStartedClientEventName =>
        val key = readKey(kryo, input, api)
        ReleaseStartedClientEvent(key)
      case ReleaseCompletedClientEventName =>
        val key = readKey(kryo, input, api)
        ReleaseCompletedClientEvent(key)
      case ReleaseAbortedClientEventName =>
        val key = readKey(kryo, input, api)
        ReleaseAbortedClientEvent(key)
      case other =>
        throw new KryoException(s"Unknown client event type: $other")
    }
    event.timestamp = kryo.readObject(input, classOf[Instant])
    event
  }
}

class ClientIndexesStateMapSerializer extends Serializer[ClientIndexesStateMap] with Helper {
  val AcquisitionPendingClientStateName = "AcquisitionPendingClientState"
  val ReleasePendingClientStateName = "ReleasePendingClientState"
  val AcquiredClientStateName = "AcquiredClientState"

  def writeApiState(kryo: Kryo, output: Output, api: UniqueIndexApi)(state: api.ClientIndexesState): Unit = {
    import api._
    output.writeInt(state.map.size, true)
    state.map.foreach { case (k, v) =>
      kryo.writeClassAndObject(output, k)
      v match {
        case AcquisitionPendingClientState() => output.writeString(AcquisitionPendingClientStateName)
        case ReleasePendingClientState() => output.writeString(ReleasePendingClientStateName)
        case AcquiredClientState() => output.writeString(AcquiredClientStateName)
      }
    }
  }

  def readApiState(kryo: Kryo, input: Input, api: UniqueIndexApi): api.ClientIndexesState = {
    import api._
    val length = input.readInt(true)
    ClientIndexesState((1 to length).map { _ =>
      val k = readKey(kryo, input, api)
      val v = input.readString() match {
        case AcquisitionPendingClientStateName => AcquisitionPendingClientState()
        case ReleasePendingClientStateName => ReleasePendingClientState()
        case AcquiredClientStateName => AcquiredClientState()
        case other => throw new KryoException(s"Unknown client index state type: $other")
      }
      k -> v
    }.toMap)
  }

  override def write(kryo: Kryo, output: Output, obj: ClientIndexesStateMap): Unit = {
    output.writeInt(obj.map.size, true)
    obj.map.values.foreach { state =>
      kryo.writeObject(output, state.Api)
      writeApiState(kryo, output, state.Api)(state.reflect)
    }
  }

  override def read(kryo: Kryo, input: Input, `type`: Class[ClientIndexesStateMap]): ClientIndexesStateMap = {
    val length = input.readInt(true)
    ClientIndexesStateMap((1 to length).map { _ =>
      val api = kryo.readObject(input, classOf[UniqueIndexApi])
      val state = readApiState(kryo, input, api)
      api -> state
    }.toMap)
  }
}

class ErrorSerializer extends Serializer[UniqueIndexApi#Error] with Helper {
  val DuplicateIndexName = "DuplicateIndex"
  val IndexIsFreeName = "IndexIsFree"
  val IndexIsAcquiredName = "IndexIsAcquired"
  val EntityIdMismatchName = "EntityIdMismatch"

  override def write(kryo: Kryo, output: Output, obj: UniqueIndexApi#Error): Unit = {
    import obj.Api._
    kryo.writeObject(output, obj.Api)
    obj.reflect match {
      case DuplicateIndex(entityId, key) =>
        output.writeString(DuplicateIndexName)
        kryo.writeClassAndObject(output, entityId)
        kryo.writeClassAndObject(output, key)
      case IndexIsFree(entityId, key) =>
        output.writeString(IndexIsFreeName)
        kryo.writeClassAndObject(output, entityId)
        kryo.writeClassAndObject(output, key)
      case IndexIsAcquired(entityId, key) =>
        output.writeString(IndexIsAcquiredName)
        kryo.writeClassAndObject(output, entityId)
        kryo.writeClassAndObject(output, key)
      case EntityIdMismatch(occupyingEntityId, requestedEntityId, key) =>
        output.writeString(EntityIdMismatchName)
        kryo.writeClassAndObject(output, occupyingEntityId)
        kryo.writeClassAndObject(output, requestedEntityId)
        kryo.writeClassAndObject(output, key)
    }
  }

  override def read(kryo: Kryo, input: Input, `type`: Class[UniqueIndexApi#Error]): UniqueIndexApi#Error = {
    val api = kryo.readObject(input, classOf[UniqueIndexApi])
    import api._
    input.readString() match {
      case DuplicateIndexName =>
        val entityId = readEntityId(kryo, input, api)
        val key = readKey(kryo, input, api)
        DuplicateIndex(entityId, key)
      case IndexIsFreeName =>
        val entityId = readEntityId(kryo, input, api)
        val key = readKey(kryo, input, api)
        IndexIsFree(entityId, key)
      case IndexIsAcquiredName =>
        val entityId = readEntityId(kryo, input, api)
        val key = readKey(kryo, input, api)
        IndexIsAcquired(entityId, key)
      case EntityIdMismatchName =>
        val occupyingEntityId = readEntityId(kryo, input, api)
        val requestedEntityId = readEntityId(kryo, input, api)
        val key = readKey(kryo, input, api)
        EntityIdMismatch(occupyingEntityId, requestedEntityId, key)
      case other => throw new KryoException(s"Unknown error type: $other")
    }
  }
}

class ServerEventSerializer extends Serializer[UniqueIndexApi#ServerEvent] with Helper {
  val AcquisitionStartedServerEventName   = "AcquisitionStartedServerEvent"
  val AcquisitionCompletedServerEventName = "AcquisitionCompletedServerEvent"
  val ReleaseStartedServerEventName       = "ReleaseStartedServerEvent"
  val ReleaseCompletedServerEventName     = "ReleaseCompletedServerEvent"

  override def write(kryo: Kryo, output: Output, obj: UniqueIndexApi#ServerEvent): Unit = {
    import obj.Api._
    kryo.writeObject(output, obj.Api)
    obj.reflect match {
      case AcquisitionStartedServerEvent(entityId) =>
        output.writeString(AcquisitionStartedServerEventName)
        kryo.writeClassAndObject(output, entityId)
      case AcquisitionCompletedServerEvent() =>
        output.writeString(AcquisitionCompletedServerEventName)
      case ReleaseStartedServerEvent() =>
        output.writeString(ReleaseStartedServerEventName)
      case ReleaseCompletedServerEvent() =>
        output.writeString(ReleaseCompletedServerEventName)
    }
  }

  override def read(kryo: Kryo, input: Input, `type`: Class[UniqueIndexApi#ServerEvent]): UniqueIndexApi#ServerEvent = {
    val api = kryo.readObject(input, classOf[UniqueIndexApi])
    import api._
    input.readString() match {
      case AcquisitionStartedServerEventName =>
        val entityId = readEntityId(kryo, input, api)
        AcquisitionStartedServerEvent(entityId)
      case AcquisitionCompletedServerEventName =>
        AcquisitionCompletedServerEvent()
      case ReleaseStartedServerEventName =>
        ReleaseStartedServerEvent()
      case ReleaseCompletedServerEventName =>
        ReleaseCompletedServerEvent()
      case other => throw new KryoException(s"Unknown server event type: $other")
    }
  }
}

class ServerStateSerializer extends Serializer[UniqueIndexApi#UniqueIndexServerState] with Helper {
  val FreeServerStateName = "FreeServerState"
  val UnconfirmedServerStateName = "UnconfirmedServerState"
  val AcquiredServerStateName = "AcquiredServerState"

  override def write(kryo: Kryo, output: Output, obj: UniqueIndexApi#UniqueIndexServerState): Unit = {
    import obj.Api._
    kryo.writeObject(output, obj.Api)
    obj.reflect match {
      case FreeServerState() =>
        output.writeString(FreeServerStateName)
      case UnconfirmedServerState(entityId) =>
        output.writeString(UnconfirmedServerStateName)
        kryo.writeClassAndObject(output, entityId)
      case AcquiredServerState(entityId) =>
        output.writeString(AcquiredServerStateName)
        kryo.writeClassAndObject(output, entityId)
    }
  }

  override def read(kryo: Kryo, input: Input, `type`: Class[UniqueIndexApi#UniqueIndexServerState]): UniqueIndexApi#UniqueIndexServerState = {
    val api = kryo.readObject(input, classOf[UniqueIndexApi])
    import api._
    input.readString() match {
      case FreeServerStateName =>
        FreeServerState()
      case UnconfirmedServerStateName =>
        val entityId = readEntityId(kryo, input, api)
        UnconfirmedServerState(entityId)
      case AcquiredServerStateName =>
        val entityId = readEntityId(kryo, input, api)
        AcquiredServerState(entityId)
      case other => throw new KryoException(s"Unknown server state type: $other")
    }
  }
}

class UniqueIndexRequestSerializer extends Serializer[UniqueIndexApi#UniqueIndexRequest[_]] with Helper {
  val GetEntityIdName         = "GetEntityId"
  val StartAcquisitionName    = "StartAcquisition"
  val CommitAcquisitionName   = "CommitAcquisition"
  val RollbackAcquisitionName = "RollbackAcquisition"
  val StartReleaseName        = "StartRelease"
  val CommitReleaseName       = "CommitRelease"
  val RollbackReleaseName     = "RollbackRelease"

  override def write(kryo: Kryo, output: Output, obj: UniqueIndexApi#UniqueIndexRequest[_]): Unit = {
    import obj.Api._
    kryo.writeObject(output, obj.Api)
    obj.reflect match {
      case GetEntityId(key) =>
        output.writeString(GetEntityIdName)
        kryo.writeClassAndObject(output, key)
      case StartAcquisition(entityId, key) =>
        output.writeString(StartAcquisitionName)
        kryo.writeClassAndObject(output, entityId)
        kryo.writeClassAndObject(output, key)
      case CommitAcquisition(entityId, key) =>
        output.writeString(CommitAcquisitionName)
        kryo.writeClassAndObject(output, entityId)
        kryo.writeClassAndObject(output, key)
      case RollbackAcquisition(entityId, key) =>
        output.writeString(RollbackAcquisitionName)
        kryo.writeClassAndObject(output, entityId)
        kryo.writeClassAndObject(output, key)
      case StartRelease(entityId, key) =>
        output.writeString(StartReleaseName)
        kryo.writeClassAndObject(output, entityId)
        kryo.writeClassAndObject(output, key)
      case CommitRelease(entityId, key) =>
        output.writeString(CommitReleaseName)
        kryo.writeClassAndObject(output, entityId)
        kryo.writeClassAndObject(output, key)
      case RollbackRelease(entityId, key) =>
        output.writeString(RollbackReleaseName)
        kryo.writeClassAndObject(output, entityId)
        kryo.writeClassAndObject(output, key)
    }
  }

  override def read(kryo: Kryo, input: Input, `type`: Class[UniqueIndexApi#UniqueIndexRequest[_]]): UniqueIndexApi#UniqueIndexRequest[_] = {
    val api = kryo.readObject(input, classOf[UniqueIndexApi])
    import api._
    input.readString() match {
      case GetEntityIdName =>
        val key = readKey(kryo, input, api)
        GetEntityId(key)
      case StartAcquisitionName =>
        val entityId = readEntityId(kryo, input, api)
        val key = readKey(kryo, input, api)
        StartAcquisition(entityId, key)
      case CommitAcquisitionName =>
        val entityId = readEntityId(kryo, input, api)
        val key = readKey(kryo, input, api)
        CommitAcquisition(entityId, key)
      case RollbackAcquisitionName =>
        val entityId = readEntityId(kryo, input, api)
        val key = readKey(kryo, input, api)
        RollbackAcquisition(entityId, key)
      case StartReleaseName =>
        val entityId = readEntityId(kryo, input, api)
        val key = readKey(kryo, input, api)
        StartRelease(entityId, key)
      case CommitReleaseName =>
        val entityId = readEntityId(kryo, input, api)
        val key = readKey(kryo, input, api)
        CommitRelease(entityId, key)
      case RollbackReleaseName =>
        val entityId = readEntityId(kryo, input, api)
        val key = readKey(kryo, input, api)
        RollbackRelease(entityId, key)
      case other => throw new KryoException(s"Unknown request type: $other")
    }

  }
}

object UniqueIndexApiSerializer {
  def registerSerializers(kryo: Kryo): Unit = {
    kryo.addDefaultSerializer(classOf[UniqueIndexApi], new ApiSerializer)
    kryo.addDefaultSerializer(classOf[UniqueIndexApi#ClientEvent], new ClientEventSerializer)
    kryo.register(classOf[ClientIndexesStateMap], new ClientIndexesStateMapSerializer)
    kryo.addDefaultSerializer(classOf[UniqueIndexApi#Error], new ErrorSerializer)
    kryo.addDefaultSerializer(classOf[UniqueIndexApi#ServerEvent], new ServerEventSerializer)
    kryo.addDefaultSerializer(classOf[UniqueIndexApi#UniqueIndexServerState], new ServerStateSerializer)
    kryo.addDefaultSerializer(classOf[UniqueIndexApi#UniqueIndexRequest[_]], new UniqueIndexRequestSerializer)
  }
}