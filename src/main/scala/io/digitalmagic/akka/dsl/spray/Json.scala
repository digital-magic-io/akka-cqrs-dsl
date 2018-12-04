package io.digitalmagic.akka.dsl.spray

import java.time.Instant

import io.digitalmagic.akka.dsl.{ClientIndexesStateMap, EventSourcedActorWithInterpreter, PersistentState, UniqueIndexApi}
import spray.json._

private object JsonHelper {
  import DefaultJsonProtocol._

  @inline implicit def entityIdFormat[I <: UniqueIndexApi](implicit api: I): JsonFormat[api.EntityIdType] = new JsonFormat[api.EntityIdType] {
    override def read(json: JsValue): api.EntityIdType = api.entityIdToString.fromString(json.convertTo[String]) match {
      case Some(v) => v
      case None => deserializationError(s"illegal entity id: $json")
    }
    override def write(obj: api.EntityIdType): JsValue = JsString(api.entityIdToString.asString(obj))
  }

  @inline implicit def valueFormat[I <: UniqueIndexApi](implicit api: I): JsonFormat[api.ValueType] = new JsonFormat[api.ValueType] {
    override def read(json: JsValue): api.ValueType = api.valueTypeToString.fromString(json.convertTo[String]) match {
      case Some(v) => v
      case None => deserializationError(s"illegal value type: $json")
    }
    override def write(obj: api.ValueType): JsValue = JsString(api.valueTypeToString.asString(obj))
  }

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

  val DuplicateIndexName = "DuplicateIndex"
  val IndexIsFreeName = "IndexIsFree"
  val IndexIsAcquiredName = "IndexIsAcquired"
  val EntityIdMismatchName = "EntityIdMismatch"

  @inline implicit def DuplicateIndexFormat[I <: UniqueIndexApi](implicit api: I):   RootJsonFormat[api.DuplicateIndex]   = jsonFormat2(api.DuplicateIndex)
  @inline implicit def IndexIsFreeFormat[I <: UniqueIndexApi](implicit api: I):      RootJsonFormat[api.IndexIsFree]      = jsonFormat2(api.IndexIsFree)
  @inline implicit def IndexIsAcquiredFormat[I <: UniqueIndexApi](implicit api: I):  RootJsonFormat[api.IndexIsAcquired]  = jsonFormat2(api.IndexIsAcquired)
  @inline implicit def EntityIdMismatchFormat[I <: UniqueIndexApi](implicit api: I): RootJsonFormat[api.EntityIdMismatch] = jsonFormat3(api.EntityIdMismatch)

  val AcquisitionStartedServerEventName   = "AcquisitionStartedServerEvent"
  val AcquisitionCompletedServerEventName = "AcquisitionCompletedServerEvent"
  val ReleaseStartedServerEventName       = "ReleaseStartedServerEvent"
  val ReleaseCompletedServerEventName     = "ReleaseCompletedServerEvent"

  @inline implicit def acquisitionStartedServerEventFormat[I <: UniqueIndexApi](implicit api: I):   RootJsonFormat[api.AcquisitionStartedServerEvent]   = jsonFormat1(api.AcquisitionStartedServerEvent)
  @inline implicit def acquisitionCompletedServerEventFormat[I <: UniqueIndexApi](implicit api: I): RootJsonFormat[api.AcquisitionCompletedServerEvent] = jsonFormat0(api.AcquisitionCompletedServerEvent)
  @inline implicit def releaseStartedServerEventFormat[I <: UniqueIndexApi](implicit api: I):       RootJsonFormat[api.ReleaseStartedServerEvent]       = jsonFormat0(api.ReleaseStartedServerEvent)
  @inline implicit def releaseCompletedServerEventFormat[I <: UniqueIndexApi](implicit api: I):     RootJsonFormat[api.ReleaseCompletedServerEvent]     = jsonFormat0(api.ReleaseCompletedServerEvent)

  val FreeServerStateName = "FreeServerState"
  val UnconfirmedServerStateName = "UnconfirmedServerState"
  val AcquiredServerStateName = "AcquiredServerState"

  @inline implicit def freeServerStateFormat[I <: UniqueIndexApi](implicit api: I):        RootJsonFormat[api.FreeServerState]        = jsonFormat0(api.FreeServerState)
  @inline implicit def unconfirmedServerStateFormat[I <: UniqueIndexApi](implicit api: I): RootJsonFormat[api.UnconfirmedServerState] = jsonFormat1(api.UnconfirmedServerState)
  @inline implicit def acquiredServerStateFormat[I <: UniqueIndexApi](implicit api: I):    RootJsonFormat[api.AcquiredServerState]    = jsonFormat1(api.AcquiredServerState)

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
      val typeName = obj match {
        case api.AcquisitionPendingClientState() => AcquisitionPendingClientStateName
        case api.ReleasePendingClientState()     => ReleasePendingClientStateName
        case api.AcquiredClientState()           => AcquiredClientStateName
      }

      JsObject(
        "type" -> typeName.toJson
      )
    }
  }

  @inline implicit def clientIndexesStateFormat[I <: UniqueIndexApi](implicit api: I): JsonFormat[api.ClientIndexesState] = new JsonFormat[api.ClientIndexesState] {
    override def read(json: JsValue): api.ClientIndexesState = {
      val fields = json.asJsObject.fields
      api.ClientIndexesState(fields.map { case (k, v) =>
        api.valueTypeToString.fromString(k) match {
          case Some(value) => value -> v.convertTo[api.ClientIndexState]
          case None => deserializationError(s"illegal value type: $k")
        }
      })
    }

    override def write(obj: api.ClientIndexesState): JsValue = {
      JsObject(obj.map.map { case (k, v) =>
        api.valueTypeToString.asString(k) -> v.toJson
      })
    }
  }

  val GetEntityIdName         = "GetEntityId"
  val StartAcquisitionName    = "StartAcquisition"
  val CommitAcquisitionName   = "CommitAcquisition"
  val RollbackAcquisitionName = "RollbackAcquisition"
  val StartReleaseName        = "StartRelease"
  val CommitReleaseName       = "CommitRelease"
  val RollbackReleaseName     = "RollbackRelease"

  @inline implicit def getEntityIdFormat[I <: UniqueIndexApi](implicit api: I):         RootJsonFormat[api.GetEntityId]         = jsonFormat1(api.GetEntityId)
  @inline implicit def startAcquisitionFormat[I <: UniqueIndexApi](implicit api: I):    RootJsonFormat[api.StartAcquisition]    = jsonFormat2(api.StartAcquisition)
  @inline implicit def commitAcquisitionFormat[I <: UniqueIndexApi](implicit api: I):   RootJsonFormat[api.CommitAcquisition]   = jsonFormat2(api.CommitAcquisition)
  @inline implicit def rollbackAcquisitionFormat[I <: UniqueIndexApi](implicit api: I): RootJsonFormat[api.RollbackAcquisition] = jsonFormat2(api.RollbackAcquisition)
  @inline implicit def startReleaseFormat[I <: UniqueIndexApi](implicit api: I):        RootJsonFormat[api.StartRelease]        = jsonFormat2(api.StartRelease)
  @inline implicit def commitReleaseFormat[I <: UniqueIndexApi](implicit api: I):       RootJsonFormat[api.CommitRelease]       = jsonFormat2(api.CommitRelease)
  @inline implicit def rollbackReleaseFormat[I <: UniqueIndexApi](implicit api: I):     RootJsonFormat[api.RollbackRelease]     = jsonFormat2(api.RollbackRelease)

}

object Json extends DefaultJsonProtocol {
  import JsonHelper._

  implicit object InstantJsonFormat extends RootJsonFormat[Instant] {
    def read(json: JsValue): Instant = Instant.parse(json.convertTo[String])
    def write(obj: Instant) = JsString(obj.toString)
  }

  implicit val apiFormat: JsonFormat[UniqueIndexApi] = new JsonFormat[UniqueIndexApi] {
    override def read(json: JsValue): UniqueIndexApi = UniqueIndexApi.getApiById(json.convertTo[String])
    override def write(obj: UniqueIndexApi): JsValue = UniqueIndexApi.getApiIdFor(obj).toJson
  }

  implicit val clientEventFormat: RootJsonFormat[UniqueIndexApi#ClientEvent] = new RootJsonFormat[UniqueIndexApi#ClientEvent] {
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

  implicit val clientIndexesStateMapFormat: JsonFormat[ClientIndexesStateMap] = new JsonFormat[ClientIndexesStateMap] {
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

  def clientStateFormat(persistentStateFormat: JsonFormat[PersistentState]): RootJsonFormat[EventSourcedActorWithInterpreter.EventSourcedActorState[PersistentState]] = new RootJsonFormat[EventSourcedActorWithInterpreter.EventSourcedActorState[PersistentState]] {
    override def read(json: JsValue): EventSourcedActorWithInterpreter.EventSourcedActorState[PersistentState] = {
      val fields = json.asJsObject.fields
      EventSourcedActorWithInterpreter.EventSourcedActorState(
        persistentStateFormat.read(fields("underlying")),
        fields("indexesState").convertTo[ClientIndexesStateMap]
      )
    }

    override def write(obj: EventSourcedActorWithInterpreter.EventSourcedActorState[PersistentState]): JsValue = {
      JsObject(
        "underlying" -> persistentStateFormat.write(obj.underlying),
        "indexesState" -> obj.indexesState.toJson
      )
    }
  }

  implicit val errorFormat: RootJsonFormat[UniqueIndexApi#Error] = new RootJsonFormat[UniqueIndexApi#Error] {
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

  implicit val serverEventFormat: RootJsonFormat[UniqueIndexApi#ServerEvent] = new RootJsonFormat[UniqueIndexApi#ServerEvent] {
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

  implicit val serverStateFormat: RootJsonFormat[UniqueIndexApi#UniqueIndexServerState] = new RootJsonFormat[UniqueIndexApi#UniqueIndexServerState] {
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

  implicit val uniqueIndexRequestFormat: RootJsonFormat[UniqueIndexApi#UniqueIndexRequest[_]] = new RootJsonFormat[UniqueIndexApi#UniqueIndexRequest[_]] {
    override def read(json: JsValue): UniqueIndexApi#UniqueIndexRequest[_] = {
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

    override def write(obj: UniqueIndexApi#UniqueIndexRequest[_]): JsValue = {
      import obj.Api
      import obj.Api._

      val (requestType, value) = obj.reflect match {
        case r: GetEntityId         => (GetEntityIdName, r.toJson)
        case r: StartAcquisition    => (StartAcquisitionName, r.toJson)
        case r: CommitAcquisition   => (CommitAcquisitionName, r.toJson)
        case r: RollbackAcquisition => (RollbackAcquisitionName, r.toJson)
        case r: StartRelease        => (StartReleaseName, r.toJson)
        case r: CommitRelease       => (CommitReleaseName, r.toJson)
        case r: RollbackRelease     => (RollbackReleaseName, r.toJson)
      }

      JsObject(
        "api" -> obj.Api.toJson,
        "type" -> requestType.toJson,
        "value" -> value
      )
    }
  }
}
