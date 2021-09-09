package io.digitalmagic.akka.dsl.spray

import io.digitalmagic.akka.dsl.context.SerializedProgramContext
import io.digitalmagic.akka.dsl.{ClientIndexesStateMap, EventSourcedActorWithInterpreter, PersistentState, UniqueIndexApi}
import spray.json._

import java.time.Instant

private object JsonHelper {
  import DefaultJsonProtocol._

  @inline implicit def entityIdFormat[I <: UniqueIndexApi](implicit api: I): JsonFormat[api.EntityIdType] = new JsonFormat[api.EntityIdType] {
    override def read(json: JsValue): api.EntityIdType = api.entityIdToString.fromString(json.convertTo[String]) match {
      case Some(v) => v
      case None => deserializationError(s"illegal entity id: $json")
    }
    override def write(obj: api.EntityIdType): JsValue = JsString(api.entityIdToString.asString(obj))
  }

  @inline implicit def keyFormat[I <: UniqueIndexApi](implicit api: I): JsonFormat[api.KeyType] = new JsonFormat[api.KeyType] {
    override def read(json: JsValue): api.KeyType = api.keyToString.fromString(json.convertTo[String]) match {
      case Some(v) => v
      case None => deserializationError(s"illegal value type: $json")
    }
    override def write(obj: api.KeyType): JsValue = JsString(api.keyToString.asString(obj))
  }

  object ClientEvents {
    val AcquisitionStartedClientEventName   = "AcquisitionStartedClientEvent"
    val AcquisitionCompletedClientEventName = "AcquisitionCompletedClientEvent"
    val AcquisitionAbortedClientEventName   = "AcquisitionAbortedClientEvent"
    val ReleaseStartedClientEventName       = "ReleaseStartedClientEvent"
    val ReleaseCompletedClientEventName     = "ReleaseCompletedClientEvent"
    val ReleaseAbortedClientEventName       = "ReleaseAbortedClientEvent"

    @inline implicit def acquisitionStartedClientEventFormat[I <: UniqueIndexApi](implicit api: I):   RootJsonFormat[api.AcquisitionStartedClientEvent]   = jsonFormat1(api.AcquisitionStartedClientEvent)
    @inline implicit def acquisitionCompletedClientEventFormat[I <: UniqueIndexApi](implicit api: I): RootJsonFormat[api.AcquisitionCompletedClientEvent] = jsonFormat1(api.AcquisitionCompletedClientEvent)
    @inline implicit def acquisitionAbortedClientEventFormat[I <: UniqueIndexApi](implicit api: I):   RootJsonFormat[api.AcquisitionAbortedClientEvent]   = jsonFormat1(api.AcquisitionAbortedClientEvent)
    @inline implicit def releaseStartedClientEventFormat[I <: UniqueIndexApi](implicit api: I):       RootJsonFormat[api.ReleaseStartedClientEvent]       = jsonFormat1(api.ReleaseStartedClientEvent)
    @inline implicit def releaseCompletedClientEventFormat[I <: UniqueIndexApi](implicit api: I):     RootJsonFormat[api.ReleaseCompletedClientEvent]     = jsonFormat1(api.ReleaseCompletedClientEvent)
    @inline implicit def releaseAbortedClientEventFormat[I <: UniqueIndexApi](implicit api: I):       RootJsonFormat[api.ReleaseAbortedClientEvent]       = jsonFormat1(api.ReleaseAbortedClientEvent)
  }

  object Errors {
    val DuplicateIndexName = "DuplicateIndex"
    val IndexIsFreeName = "IndexIsFree"
    val IndexIsAcquiredName = "IndexIsAcquired"
    val EntityIdMismatchName = "EntityIdMismatch"

    @inline implicit def DuplicateIndexFormat[I <: UniqueIndexApi](implicit api: I):   RootJsonFormat[api.DuplicateIndex]   = jsonFormat2(api.DuplicateIndex)
    @inline implicit def IndexIsFreeFormat[I <: UniqueIndexApi](implicit api: I):      RootJsonFormat[api.IndexIsFree]      = jsonFormat2(api.IndexIsFree)
    @inline implicit def IndexIsAcquiredFormat[I <: UniqueIndexApi](implicit api: I):  RootJsonFormat[api.IndexIsAcquired]  = jsonFormat2(api.IndexIsAcquired)
    @inline implicit def EntityIdMismatchFormat[I <: UniqueIndexApi](implicit api: I): RootJsonFormat[api.EntityIdMismatch] = jsonFormat3(api.EntityIdMismatch)
  }

  object ServerEvents {
    val AcquisitionStartedServerEventName   = "AcquisitionStartedServerEvent"
    val AcquisitionCompletedServerEventName = "AcquisitionCompletedServerEvent"
    val ReleaseStartedServerEventName       = "ReleaseStartedServerEvent"
    val ReleaseCompletedServerEventName     = "ReleaseCompletedServerEvent"

    @inline implicit def acquisitionStartedServerEventFormat[I <: UniqueIndexApi](implicit api: I):   RootJsonFormat[api.AcquisitionStartedServerEvent]   = jsonFormat1(api.AcquisitionStartedServerEvent)
    @inline implicit def acquisitionCompletedServerEventFormat[I <: UniqueIndexApi](implicit api: I): RootJsonFormat[api.AcquisitionCompletedServerEvent] = jsonFormat0(api.AcquisitionCompletedServerEvent)
    @inline implicit def releaseStartedServerEventFormat[I <: UniqueIndexApi](implicit api: I):       RootJsonFormat[api.ReleaseStartedServerEvent]       = jsonFormat0(api.ReleaseStartedServerEvent)
    @inline implicit def releaseCompletedServerEventFormat[I <: UniqueIndexApi](implicit api: I):     RootJsonFormat[api.ReleaseCompletedServerEvent]     = jsonFormat0(api.ReleaseCompletedServerEvent)
  }

  object ServerState {
    val FreeServerStateName = "FreeServerState"
    val UnconfirmedServerStateName = "UnconfirmedServerState"
    val AcquiredServerStateName = "AcquiredServerState"

    @inline implicit def freeServerStateFormat[I <: UniqueIndexApi](implicit api: I):        RootJsonFormat[api.FreeServerState]        = jsonFormat0(api.FreeServerState)
    @inline implicit def unconfirmedServerStateFormat[I <: UniqueIndexApi](implicit api: I): RootJsonFormat[api.UnconfirmedServerState] = jsonFormat1(api.UnconfirmedServerState)
    @inline implicit def acquiredServerStateFormat[I <: UniqueIndexApi](implicit api: I):    RootJsonFormat[api.AcquiredServerState]    = jsonFormat1(api.AcquiredServerState)
  }

  val AcquisitionPendingClientStateName = "AcquisitionPendingClientState"
  val ReleasePendingClientStateName     = "ReleasePendingClientState"
  val AcquiredClientStateName           = "AcquiredClientState"

  @inline implicit def clientIndexStateFormat[I <: UniqueIndexApi](implicit api: I): JsonFormat[api.ClientIndexState] = new JsonFormat[api.ClientIndexState] {
    override def read(json: JsValue): api.ClientIndexState = {
      val fields = json.asJsObject.fields
      fields("type").convertTo[String] match {
        case AcquisitionPendingClientStateName => api.AcquisitionPendingClientState()
        case ReleasePendingClientStateName     => api.ReleasePendingClientState()
        case AcquiredClientStateName           => api.AcquiredClientState()
        case other                             => deserializationError(s"unknown client index state type: $other")
      }
    }

    override def write(obj: api.ClientIndexState): JsValue = {
      @inline def getTypeName(api: UniqueIndexApi)(obj: api.ClientIndexState): String = obj match {
        case api.AcquisitionPendingClientState() => AcquisitionPendingClientStateName
        case api.ReleasePendingClientState()     => ReleasePendingClientStateName
        case api.AcquiredClientState()           => AcquiredClientStateName
      }
      val typeName = getTypeName(api)(obj)

      JsObject(
        "type" -> typeName.toJson
      )
    }
  }

  @inline implicit def clientIndexesStateFormat[I <: UniqueIndexApi](implicit api: I): JsonFormat[api.ClientIndexesState] = new JsonFormat[api.ClientIndexesState] {
    override def read(json: JsValue): api.ClientIndexesState = {
      val fields = json.asJsObject.fields
      api.ClientIndexesState(fields.map { case (k, v) =>
        api.keyToString.fromString(k) match {
          case Some(value) => value -> v.convertTo[api.ClientIndexState]
          case None => deserializationError(s"illegal value type: $k")
        }
      })
    }

    override def write(obj: api.ClientIndexesState): JsValue = {
      JsObject(obj.map.map { case (k, v) =>
        api.keyToString.asString(k) -> v.toJson
      })
    }
  }

  class Requests(implicit jf: JsonFormat[SerializedProgramContext]) {
    val GetEntityIdName         = "GetEntityId"
    val StartAcquisitionName    = "StartAcquisition"
    val CommitAcquisitionName   = "CommitAcquisition"
    val RollbackAcquisitionName = "RollbackAcquisition"
    val StartReleaseName        = "StartRelease"
    val CommitReleaseName       = "CommitRelease"
    val RollbackReleaseName     = "RollbackRelease"

    @inline implicit def getEntityIdFormat[I <: UniqueIndexApi](implicit api: I): RootJsonFormat[api.GetEntityId] = new RootJsonFormat[api.GetEntityId] {
      override def read(json: JsValue): api.GetEntityId = {
        val fields = json.asJsObject.fields
        implicit val context = fields("context").convertTo[SerializedProgramContext]
        api.GetEntityId(fields("key").convertTo[api.KeyType])
      }
      override def write(obj: api.GetEntityId): JsValue = {
        JsObject("key" -> obj.key.toJson, "context" -> obj.context.toJson)
      }
    }

    @inline implicit def startAcquisitionFormat[I <: UniqueIndexApi](implicit api: I): RootJsonFormat[api.StartAcquisition] = new RootJsonFormat[api.StartAcquisition] {
      override def read(json: JsValue): api.StartAcquisition = {
        val fields = json.asJsObject.fields
        implicit val context = fields("context").convertTo[SerializedProgramContext]
        api.StartAcquisition(fields("entityId").convertTo[api.EntityIdType], fields("key").convertTo[api.KeyType])
      }
      override def write(obj: api.StartAcquisition): JsValue = {
        JsObject("entityId" ->obj.entityId.toJson, "key" -> obj.key.toJson, "context" -> obj.context.toJson)
      }
    }

    @inline implicit def commitAcquisitionFormat[I <: UniqueIndexApi](implicit api: I): RootJsonFormat[api.CommitAcquisition] = new RootJsonFormat[api.CommitAcquisition] {
      override def read(json: JsValue): api.CommitAcquisition = {
        val fields = json.asJsObject.fields
        implicit val context = fields("context").convertTo[SerializedProgramContext]
        api.CommitAcquisition(fields("entityId").convertTo[api.EntityIdType], fields("key").convertTo[api.KeyType])
      }
      override def write(obj: api.CommitAcquisition): JsValue = {
        JsObject("entityId" ->obj.entityId.toJson, "key" -> obj.key.toJson, "context" -> obj.context.toJson)
      }
    }

    @inline implicit def rollbackAcquisitionFormat[I <: UniqueIndexApi](implicit api: I): RootJsonFormat[api.RollbackAcquisition] = new RootJsonFormat[api.RollbackAcquisition] {
      override def read(json: JsValue): api.RollbackAcquisition = {
        val fields = json.asJsObject.fields
        implicit val context = fields("context").convertTo[SerializedProgramContext]
        api.RollbackAcquisition(fields("entityId").convertTo[api.EntityIdType], fields("key").convertTo[api.KeyType])
      }
      override def write(obj: api.RollbackAcquisition): JsValue = {
        JsObject("entityId" ->obj.entityId.toJson, "key" -> obj.key.toJson, "context" -> obj.context.toJson)
      }
    }

    @inline implicit def startReleaseFormat[I <: UniqueIndexApi](implicit api: I): RootJsonFormat[api.StartRelease] = new RootJsonFormat[api.StartRelease] {
      override def read(json: JsValue): api.StartRelease = {
        val fields = json.asJsObject.fields
        implicit val context = fields("context").convertTo[SerializedProgramContext]
        api.StartRelease(fields("entityId").convertTo[api.EntityIdType], fields("key").convertTo[api.KeyType])
      }
      override def write(obj: api.StartRelease): JsValue = {
        JsObject("entityId" ->obj.entityId.toJson, "key" -> obj.key.toJson, "context" -> obj.context.toJson)
      }
    }

    @inline implicit def commitReleaseFormat[I <: UniqueIndexApi](implicit api: I): RootJsonFormat[api.CommitRelease] = new RootJsonFormat[api.CommitRelease] {
      override def read(json: JsValue): api.CommitRelease = {
        val fields = json.asJsObject.fields
        implicit val context = fields("context").convertTo[SerializedProgramContext]
        api.CommitRelease(fields("entityId").convertTo[api.EntityIdType], fields("key").convertTo[api.KeyType])
      }
      override def write(obj: api.CommitRelease): JsValue = {
        JsObject("entityId" ->obj.entityId.toJson, "key" -> obj.key.toJson, "context" -> obj.context.toJson)
      }
    }

    @inline implicit def rollbackReleaseFormat[I <: UniqueIndexApi](implicit api: I): RootJsonFormat[api.RollbackRelease] = new RootJsonFormat[api.RollbackRelease] {
      override def read(json: JsValue): api.RollbackRelease = {
        val fields = json.asJsObject.fields
        implicit val context = fields("context").convertTo[SerializedProgramContext]
        api.RollbackRelease(fields("entityId").convertTo[api.EntityIdType], fields("key").convertTo[api.KeyType])
      }
      override def write(obj: api.RollbackRelease): JsValue = {
        JsObject("entityId" ->obj.entityId.toJson, "key" -> obj.key.toJson, "context" -> obj.context.toJson)
      }
    }
  }
}

object Json extends DefaultJsonProtocol {
  import JsonHelper._

  implicit object JavaInstantJsonFormat extends RootJsonFormat[Instant] {
    def read(json: JsValue): Instant = Instant.parse(json.convertTo[String])
    def write(obj: Instant) = JsString(obj.toString)
  }

  import scala.reflect.runtime.universe.Mirror
  implicit def apiFormat(implicit mirror: Mirror): JsonFormat[UniqueIndexApi] = new JsonFormat[UniqueIndexApi] {
    override def read(json: JsValue): UniqueIndexApi = UniqueIndexApi.getApiById(json.convertTo[String])
    override def write(obj: UniqueIndexApi): JsValue = UniqueIndexApi.getApiIdFor(obj).toJson
  }

  implicit def clientEventFormat(implicit mirror: Mirror): RootJsonFormat[UniqueIndexApi#ClientEvent] = new RootJsonFormat[UniqueIndexApi#ClientEvent] {
    import ClientEvents._

    override def read(json: JsValue): UniqueIndexApi#ClientEvent = {
      val fields = json.asJsObject.fields
      val value = fields("value")

      implicit val Api = fields("api").convertTo[UniqueIndexApi]
      import Api._

      val result = fields("type").convertTo[String] match {
        case AcquisitionStartedClientEventName   => value.convertTo[AcquisitionStartedClientEvent]
        case AcquisitionCompletedClientEventName => value.convertTo[AcquisitionCompletedClientEvent]
        case AcquisitionAbortedClientEventName   => value.convertTo[AcquisitionAbortedClientEvent]
        case ReleaseStartedClientEventName       => value.convertTo[ReleaseStartedClientEvent]
        case ReleaseCompletedClientEventName     => value.convertTo[ReleaseCompletedClientEvent]
        case ReleaseAbortedClientEventName       => value.convertTo[ReleaseAbortedClientEvent]
        case other                               => deserializationError(s"unknown client event type: $other")
      }
      result.timestamp = fields("timestamp").convertTo[Instant]
      result
    }

    override def write(obj: UniqueIndexApi#ClientEvent): JsValue = {
      import obj.Api
      import obj.Api._

      val (eventType, value) = obj.reflect match {
        case e: AcquisitionStartedClientEvent   => (AcquisitionStartedClientEventName,   e.toJson)
        case e: AcquisitionCompletedClientEvent => (AcquisitionCompletedClientEventName, e.toJson)
        case e: AcquisitionAbortedClientEvent   => (AcquisitionAbortedClientEventName,   e.toJson)
        case e: ReleaseStartedClientEvent       => (ReleaseStartedClientEventName,       e.toJson)
        case e: ReleaseCompletedClientEvent     => (ReleaseCompletedClientEventName,     e.toJson)
        case e: ReleaseAbortedClientEvent       => (ReleaseAbortedClientEventName,       e.toJson)
      }

      JsObject(
        "api" -> obj.Api.toJson,
        "type" -> eventType.toJson,
        "timestamp" -> obj.timestamp.toJson,
        "value" -> value
      )
    }
  }

  implicit def clientIndexesStateMapFormat(implicit mirror: Mirror): JsonFormat[ClientIndexesStateMap] = new JsonFormat[ClientIndexesStateMap] {
    override def read(json: JsValue): ClientIndexesStateMap = {
      val fields = json.asJsObject.fields
      ClientIndexesStateMap(
        fields.map { case (k, v) =>
          val api = UniqueIndexApi.getApiById(k)
          api -> clientIndexesStateFormat(api).read(v)
        }
      )
    }

    override def write(obj: ClientIndexesStateMap): JsValue = {
      JsObject(obj.map.map { case (_, v) =>
        UniqueIndexApi.getApiIdFor(v.Api) -> clientIndexesStateFormat(v.Api).write(v.reflect)
      })
    }
  }

  implicit def clientStateFormat[T <: PersistentState](implicit persistentStateFormat: JsonFormat[T], mirror: Mirror): RootJsonFormat[EventSourcedActorWithInterpreter.EventSourcedActorState[T]] = new RootJsonFormat[EventSourcedActorWithInterpreter.EventSourcedActorState[T]] {
    override def read(json: JsValue): EventSourcedActorWithInterpreter.EventSourcedActorState[T] = {
      val fields = json.asJsObject.fields
      EventSourcedActorWithInterpreter.EventSourcedActorState(
        persistentStateFormat.read(fields("underlying")),
        fields("indexesState").convertTo[ClientIndexesStateMap]
      )
    }

    override def write(obj: EventSourcedActorWithInterpreter.EventSourcedActorState[T]): JsValue = {
      JsObject(
        "underlying" -> persistentStateFormat.write(obj.underlying),
        "indexesState" -> obj.indexesState.toJson
      )
    }
  }

  implicit def errorFormat(implicit mirror: Mirror): RootJsonFormat[UniqueIndexApi#Error] = new RootJsonFormat[UniqueIndexApi#Error] {
    import Errors._

    override def read(json: JsValue): UniqueIndexApi#Error = {
      val fields = json.asJsObject.fields
      val value = fields("value")

      implicit val Api = fields("api").convertTo[UniqueIndexApi]
      import Api._

      fields("type").convertTo[String] match {
        case DuplicateIndexName   => value.convertTo[DuplicateIndex]
        case IndexIsFreeName      => value.convertTo[IndexIsFree]
        case IndexIsAcquiredName  => value.convertTo[IndexIsAcquired]
        case EntityIdMismatchName => value.convertTo[EntityIdMismatch]
        case other                => deserializationError(s"unknown error type: $other")
      }
    }

    override def write(obj: UniqueIndexApi#Error): JsValue = {
      import obj.Api
      import obj.Api._

      val (errorType, value) = obj.reflect match {
        case e: DuplicateIndex   => (DuplicateIndexName,   e.toJson)
        case e: IndexIsFree      => (IndexIsFreeName,      e.toJson)
        case e: IndexIsAcquired  => (IndexIsAcquiredName,  e.toJson)
        case e: EntityIdMismatch => (EntityIdMismatchName, e.toJson)
      }

      JsObject(
        "api" -> obj.Api.toJson,
        "type" -> errorType.toJson,
        "value" -> value
      )
    }
  }

  implicit def serverEventFormat(implicit mirror: Mirror): RootJsonFormat[UniqueIndexApi#ServerEvent] = new RootJsonFormat[UniqueIndexApi#ServerEvent] {
    import ServerEvents._

    override def read(json: JsValue): UniqueIndexApi#ServerEvent = {
      val fields = json.asJsObject.fields
      val value = fields("value")

      implicit val Api = fields("api").convertTo[UniqueIndexApi]
      import Api._

      val result = fields("type").convertTo[String] match {
        case AcquisitionStartedServerEventName   => value.convertTo[AcquisitionStartedServerEvent]
        case AcquisitionCompletedServerEventName => value.convertTo[AcquisitionCompletedServerEvent]
        case ReleaseStartedServerEventName       => value.convertTo[ReleaseStartedServerEvent]
        case ReleaseCompletedServerEventName     => value.convertTo[ReleaseCompletedServerEvent]
        case other                               => deserializationError(s"unknown server event type: $other")
      }
      result.timestamp = fields("timestamp").convertTo[Instant]
      result
    }

    override def write(obj: UniqueIndexApi#ServerEvent): JsValue = {
      import obj.Api
      import obj.Api._

      val (eventType, value) = obj.reflect match {
        case e: AcquisitionStartedServerEvent   => (AcquisitionStartedServerEventName,   e.toJson)
        case e: AcquisitionCompletedServerEvent => (AcquisitionCompletedServerEventName, e.toJson)
        case e: ReleaseStartedServerEvent       => (ReleaseStartedServerEventName,       e.toJson)
        case e: ReleaseCompletedServerEvent     => (ReleaseCompletedServerEventName,     e.toJson)
      }

      JsObject(
        "api" -> obj.Api.toJson,
        "type" -> eventType.toJson,
        "timestamp" -> obj.timestamp.toJson,
        "value" -> value
      )
    }
  }

  implicit def serverStateFormat(implicit mirror: Mirror): RootJsonFormat[UniqueIndexApi#UniqueIndexServerState] = new RootJsonFormat[UniqueIndexApi#UniqueIndexServerState] {
    import ServerState._

    override def read(json: JsValue): UniqueIndexApi#UniqueIndexServerState = {
      val fields = json.asJsObject.fields
      val value = fields("value")

      implicit val Api = fields("api").convertTo[UniqueIndexApi]
      import Api._

      fields("type").convertTo[String] match {
        case FreeServerStateName        => value.convertTo[FreeServerState]
        case UnconfirmedServerStateName => value.convertTo[UnconfirmedServerState]
        case AcquiredServerStateName    => value.convertTo[AcquiredServerState]
        case other                      => deserializationError(s"unknown server state type: $other")
      }
    }

    override def write(obj: UniqueIndexApi#UniqueIndexServerState): JsValue = {
      import obj.Api
      import obj.Api._

      val (stateType, value) = obj.reflect match {
        case s: FreeServerState        => (FreeServerStateName,        s.toJson)
        case s: UnconfirmedServerState => (UnconfirmedServerStateName, s.toJson)
        case s: AcquiredServerState    => (AcquiredServerStateName,    s.toJson)
      }

      JsObject(
        "api" -> obj.Api.toJson,
        "type" -> stateType.toJson,
        "value" -> value
      )
    }
  }

  implicit def uniqueIndexRequestFormat(implicit mirror: Mirror, jf: JsonFormat[SerializedProgramContext]): RootJsonFormat[UniqueIndexApi#ConcreteUniqueIndexRequest[_]] = new RootJsonFormat[UniqueIndexApi#ConcreteUniqueIndexRequest[_]] {
    private val requests = new Requests
    import requests._

    override def read(json: JsValue): UniqueIndexApi#ConcreteUniqueIndexRequest[_] = {
      val fields = json.asJsObject.fields
      val value = fields("value")

      implicit val Api = fields("api").convertTo[UniqueIndexApi]
      import Api._

      fields("type").convertTo[String] match {
        case GetEntityIdName         => value.convertTo[GetEntityId]
        case StartAcquisitionName    => value.convertTo[StartAcquisition]
        case CommitAcquisitionName   => value.convertTo[CommitAcquisition]
        case RollbackAcquisitionName => value.convertTo[RollbackAcquisition]
        case StartReleaseName        => value.convertTo[StartRelease]
        case CommitReleaseName       => value.convertTo[CommitRelease]
        case RollbackReleaseName     => value.convertTo[RollbackRelease]
        case other                   => deserializationError(s"unknown request type: $other")
      }
    }

    override def write(obj: UniqueIndexApi#ConcreteUniqueIndexRequest[_]): JsValue = {
      @inline def getTypeAndJsValue(api: UniqueIndexApi)(obj: api.ConcreteUniqueIndexRequest[_]): (String, JsValue) = {
        import obj.Api
        import obj.Api._
        obj match {
          case r: GetEntityId         => (GetEntityIdName, r.toJson)
          case r: StartAcquisition    => (StartAcquisitionName, r.toJson)
          case r: CommitAcquisition   => (CommitAcquisitionName, r.toJson)
          case r: RollbackAcquisition => (RollbackAcquisitionName, r.toJson)
          case r: StartRelease        => (StartReleaseName, r.toJson)
          case r: CommitRelease       => (CommitReleaseName, r.toJson)
          case r: RollbackRelease     => (RollbackReleaseName, r.toJson)
        }
      }

      val (requestType, value) = getTypeAndJsValue(obj.Api)(obj.reflect)
      JsObject(
        "api" -> obj.Api.toJson,
        "type" -> requestType.toJson,
        "value" -> value
      )
    }
  }
}
