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

class ClientEventSerializer extends Serializer[UniqueIndexApi#ClientEvent] {
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
        kryo.writeClassAndObject(output, e.value)
      case e: AcquisitionCompletedClientEvent =>
        output.writeString(AcquisitionCompletedClientEventName)
        kryo.writeClassAndObject(output, e.value)
      case e: AcquisitionAbortedClientEvent =>
        output.writeString(AcquisitionAbortedClientEventName)
        kryo.writeClassAndObject(output, e.value)
      case e: ReleaseStartedClientEvent =>
        output.writeString(ReleaseStartedClientEventName)
        kryo.writeClassAndObject(output, e.value)
      case e: ReleaseCompletedClientEvent =>
        output.writeString(ReleaseCompletedClientEventName)
        kryo.writeClassAndObject(output, e.value)
      case e: ReleaseAbortedClientEvent =>
        output.writeString(ReleaseAbortedClientEventName)
        kryo.writeClassAndObject(output, e.value)
    }
    kryo.writeObject(output, obj.timestamp)
  }

  override def read(kryo: Kryo, input: Input, `type`: Class[UniqueIndexApi#ClientEvent]): UniqueIndexApi#ClientEvent = {
    val api = kryo.readObject(input, classOf[UniqueIndexApi])
    import api._

    val event = input.readString() match {
      case AcquisitionStartedClientEventName =>
        val value = kryo.readClassAndObject(input).asInstanceOf[ValueType]
        AcquisitionStartedClientEvent(value)
      case AcquisitionCompletedClientEventName =>
        val value = kryo.readClassAndObject(input).asInstanceOf[ValueType]
        AcquisitionCompletedClientEvent(value)
      case AcquisitionAbortedClientEventName =>
        val value = kryo.readClassAndObject(input).asInstanceOf[ValueType]
        AcquisitionAbortedClientEvent(value)
      case ReleaseStartedClientEventName =>
        val value = kryo.readClassAndObject(input).asInstanceOf[ValueType]
        ReleaseStartedClientEvent(value)
      case ReleaseCompletedClientEventName =>
        val value = kryo.readClassAndObject(input).asInstanceOf[ValueType]
        ReleaseCompletedClientEvent(value)
      case ReleaseAbortedClientEventName =>
        val value = kryo.readClassAndObject(input).asInstanceOf[ValueType]
        ReleaseAbortedClientEvent(value)
      case other =>
        throw new KryoException(s"Unknown client event type: $other")
    }
    event.timestamp = kryo.readObject(input, classOf[Instant])
    event
  }
}

class ClientIndexesStateMapSerializer extends Serializer[ClientIndexesStateMap] {
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
      val k = kryo.readClassAndObject(input).asInstanceOf[ValueType]
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

class ErrorSerializer extends Serializer[UniqueIndexApi#Error] {
  val DuplicateIndexName = "DuplicateIndex"
  val IndexIsFreeName = "IndexIsFree"
  val IndexIsAcquiredName = "IndexIsAcquired"
  val EntityIdMismatchName = "EntityIdMismatch"

  override def write(kryo: Kryo, output: Output, obj: UniqueIndexApi#Error): Unit = {
    import obj.Api._
    kryo.writeObject(output, obj.Api)
    obj.reflect match {
      case DuplicateIndex(key, value) =>
        output.writeString(DuplicateIndexName)
        kryo.writeClassAndObject(output, key)
        kryo.writeClassAndObject(output, value)
      case IndexIsFree(key, value) =>
        output.writeString(IndexIsFreeName)
        kryo.writeClassAndObject(output, key)
        kryo.writeClassAndObject(output, value)
      case IndexIsAcquired(key, value) =>
        output.writeString(IndexIsAcquiredName)
        kryo.writeClassAndObject(output, key)
        kryo.writeClassAndObject(output, value)
      case EntityIdMismatch(occupyingKey, requestedKey, value) =>
        output.writeString(EntityIdMismatchName)
        kryo.writeClassAndObject(output, occupyingKey)
        kryo.writeClassAndObject(output, requestedKey)
        kryo.writeClassAndObject(output, value)
    }
  }

  override def read(kryo: Kryo, input: Input, `type`: Class[UniqueIndexApi#Error]): UniqueIndexApi#Error = {
    val api = kryo.readObject(input, classOf[UniqueIndexApi])
    import api._
    input.readString() match {
      case DuplicateIndexName =>
        val key = kryo.readClassAndObject(input).asInstanceOf[EntityIdType]
        val value = kryo.readClassAndObject(input).asInstanceOf[ValueType]
        DuplicateIndex(key, value)
      case IndexIsFreeName =>
        val key = kryo.readClassAndObject(input).asInstanceOf[EntityIdType]
        val value = kryo.readClassAndObject(input).asInstanceOf[ValueType]
        IndexIsFree(key, value)
      case IndexIsAcquiredName =>
        val key = kryo.readClassAndObject(input).asInstanceOf[EntityIdType]
        val value = kryo.readClassAndObject(input).asInstanceOf[ValueType]
        IndexIsAcquired(key, value)
      case EntityIdMismatchName =>
        val occupyingKey = kryo.readClassAndObject(input).asInstanceOf[EntityIdType]
        val requestedKey = kryo.readClassAndObject(input).asInstanceOf[EntityIdType]
        val value = kryo.readClassAndObject(input).asInstanceOf[ValueType]
        EntityIdMismatch(occupyingKey, requestedKey, value)
      case other => throw new KryoException(s"Unknown error type: $other")
    }
  }
}

class ServerEventSerializer extends Serializer[UniqueIndexApi#ServerEvent] {
  val AcquisitionStartedServerEventName   = "AcquisitionStartedServerEvent"
  val AcquisitionCompletedServerEventName = "AcquisitionCompletedServerEvent"
  val ReleaseStartedServerEventName       = "ReleaseStartedServerEvent"
  val ReleaseCompletedServerEventName     = "ReleaseCompletedServerEvent"

  override def write(kryo: Kryo, output: Output, obj: UniqueIndexApi#ServerEvent): Unit = {
    import obj.Api._
    kryo.writeObject(output, obj.Api)
    obj.reflect match {
      case AcquisitionStartedServerEvent(key) =>
        output.writeString(AcquisitionStartedServerEventName)
        kryo.writeClassAndObject(output, key)
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
        val key = kryo.readClassAndObject(input).asInstanceOf[EntityIdType]
        AcquisitionStartedServerEvent(key)
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

class ServerStateSerializer extends Serializer[UniqueIndexApi#UniqueIndexServerState] {
  val FreeServerStateName = "FreeServerState"
  val UnconfirmedServerStateName = "UnconfirmedServerState"
  val AcquiredServerStateName = "AcquiredServerState"

  override def write(kryo: Kryo, output: Output, obj: UniqueIndexApi#UniqueIndexServerState): Unit = {
    import obj.Api._
    kryo.writeObject(output, obj.Api)
    obj.reflect match {
      case FreeServerState() =>
        output.writeString(FreeServerStateName)
      case UnconfirmedServerState(key) =>
        output.writeString(UnconfirmedServerStateName)
        kryo.writeClassAndObject(output, key)
      case AcquiredServerState(key) =>
        output.writeString(AcquiredServerStateName)
        kryo.writeClassAndObject(output, key)
    }
  }

  override def read(kryo: Kryo, input: Input, `type`: Class[UniqueIndexApi#UniqueIndexServerState]): UniqueIndexApi#UniqueIndexServerState = {
    val api = kryo.readObject(input, classOf[UniqueIndexApi])
    import api._
    input.readString() match {
      case FreeServerStateName =>
        FreeServerState()
      case UnconfirmedServerStateName =>
        val key = kryo.readClassAndObject(input).asInstanceOf[EntityIdType]
        UnconfirmedServerState(key)
      case AcquiredServerStateName =>
        val key = kryo.readClassAndObject(input).asInstanceOf[EntityIdType]
        AcquiredServerState(key)
      case other => throw new KryoException(s"Unknown server state type: $other")
    }
  }
}

class UniqueIndexRequestSerializer extends Serializer[UniqueIndexApi#UniqueIndexRequest[_]] {
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
      case GetEntityId(value) =>
        output.writeString(GetEntityIdName)
        kryo.writeClassAndObject(output, value)
      case StartAcquisition(key, value) =>
        output.writeString(StartAcquisitionName)
        kryo.writeClassAndObject(output, key)
        kryo.writeClassAndObject(output, value)
      case CommitAcquisition(key, value) =>
        output.writeString(CommitAcquisitionName)
        kryo.writeClassAndObject(output, key)
        kryo.writeClassAndObject(output, value)
      case RollbackAcquisition(key, value) =>
        output.writeString(RollbackAcquisitionName)
        kryo.writeClassAndObject(output, key)
        kryo.writeClassAndObject(output, value)
      case StartRelease(key, value) =>
        output.writeString(StartReleaseName)
        kryo.writeClassAndObject(output, key)
        kryo.writeClassAndObject(output, value)
      case CommitRelease(key, value) =>
        output.writeString(CommitReleaseName)
        kryo.writeClassAndObject(output, key)
        kryo.writeClassAndObject(output, value)
      case RollbackRelease(key, value) =>
        output.writeString(RollbackReleaseName)
        kryo.writeClassAndObject(output, key)
        kryo.writeClassAndObject(output, value)
    }
  }

  override def read(kryo: Kryo, input: Input, `type`: Class[UniqueIndexApi#UniqueIndexRequest[_]]): UniqueIndexApi#UniqueIndexRequest[_] = {
    val api = kryo.readObject(input, classOf[UniqueIndexApi])
    import api._
    input.readString() match {
      case GetEntityIdName =>
        val value = kryo.readClassAndObject(input).asInstanceOf[ValueType]
        GetEntityId(value)
      case StartAcquisitionName =>
        val key = kryo.readClassAndObject(input).asInstanceOf[EntityIdType]
        val value = kryo.readClassAndObject(input).asInstanceOf[ValueType]
        StartAcquisition(key, value)
      case CommitAcquisitionName =>
        val key = kryo.readClassAndObject(input).asInstanceOf[EntityIdType]
        val value = kryo.readClassAndObject(input).asInstanceOf[ValueType]
        CommitAcquisition(key, value)
      case RollbackAcquisitionName =>
        val key = kryo.readClassAndObject(input).asInstanceOf[EntityIdType]
        val value = kryo.readClassAndObject(input).asInstanceOf[ValueType]
        RollbackAcquisition(key, value)
      case StartReleaseName =>
        val key = kryo.readClassAndObject(input).asInstanceOf[EntityIdType]
        val value = kryo.readClassAndObject(input).asInstanceOf[ValueType]
        StartRelease(key, value)
      case CommitReleaseName =>
        val key = kryo.readClassAndObject(input).asInstanceOf[EntityIdType]
        val value = kryo.readClassAndObject(input).asInstanceOf[ValueType]
        CommitRelease(key, value)
      case RollbackReleaseName =>
        val key = kryo.readClassAndObject(input).asInstanceOf[EntityIdType]
        val value = kryo.readClassAndObject(input).asInstanceOf[ValueType]
        RollbackRelease(key, value)
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
