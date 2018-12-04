package io.digitalmagic.akka.dsl

import akka.actor.ActorSelection
import io.digitalmagic.akka.dsl.API._
import iotaz.{Cop, CopK}
import scalaz._

object UniqueIndexApi {
  sealed trait IsIndexNeededResponse
  object IsIndexNeededResponse {
    case object Yes extends IsIndexNeededResponse
    case object Unknown extends IsIndexNeededResponse
    case object No extends IsIndexNeededResponse
  }

  type ClientEventAux[E, I <: UniqueIndexApi, T] = I { type EntityIdType = E; type ClientEventType = T }
  type IndexApiAux[E, I <: UniqueIndexApi, T[_]] = I { type EntityIdType = E; type IndexApiType[X] = T[X] }
  type ClientQueryAux[E, I <: UniqueIndexApi, T[_]] = I { type EntityIdType = E; type ClientQueryType[X] = T[X] }

  import scala.reflect.runtime.currentMirror
  def getApiIdFor(api: UniqueIndexApi): String = currentMirror.reflect(api).symbol.fullName
  def getApiById(id: String): UniqueIndexApi = currentMirror.reflectModule(currentMirror.staticModule(id)).instance.asInstanceOf[UniqueIndexApi]

  abstract class Base[E: StringRepresentable, V: StringRepresentable] extends UniqueIndexApi {
    Self: Singleton =>
    type ClientQueryType[T] = ClientQuery[T]
    type ValueType = V
    type IndexApiType[T] = IndexApi[T]
    type EntityIdType = E
    type ClientEventType = ClientEvent
    override def valueTypeToString: StringRepresentable[V] = implicitly
    override def entityIdToString: StringRepresentable[E] = implicitly
  }
}

case class ClientIndexesStateMap private(map: Map[UniqueIndexApi, UniqueIndexApi#ClientIndexesState] = Map.empty[UniqueIndexApi, UniqueIndexApi#ClientIndexesState]) {
  def get[A <: UniqueIndexApi](api: A): Option[api.ClientIndexesState] =
    map.get(api).asInstanceOf[Option[api.ClientIndexesState]]

  def modify[A <: UniqueIndexApi](api: A)(f: api.ClientIndexesState => api.ClientIndexesState): ClientIndexesStateMap = {
    val state = get(api).getOrElse(api.ClientIndexesState())
    val newState = f(state)
    ClientIndexesStateMap(map + (api -> newState))
  }

  def process[A <: UniqueIndexApi](api: A)(event: api.ClientEventType): ClientIndexesStateMap = modify(api) { state =>
    import api._
      (state.get(event.value), event) match {
        case (None,                                  AcquisitionStartedClientEvent(v))   => state + (v -> AcquisitionPendingClientState())
        case (Some(AcquisitionPendingClientState()), AcquisitionStartedClientEvent(_))   => state
        case (Some(AcquisitionPendingClientState()), AcquisitionCompletedClientEvent(v)) => state + (v -> AcquiredClientState())
        case (Some(AcquisitionPendingClientState()), AcquisitionAbortedClientEvent(v))   => state - v
        case (Some(AcquiredClientState()),           ReleaseStartedClientEvent(v))       => state + (v -> ReleasePendingClientState())
        case (Some(ReleasePendingClientState()),     ReleaseStartedClientEvent(_))       => state
        case (Some(ReleasePendingClientState()),     ReleaseCompletedClientEvent(v))     => state - v
        case (Some(ReleasePendingClientState()),     ReleaseAbortedClientEvent(v))       => state + (v -> AcquiredClientState())
        case _                                                                           => sys.error("should not happen")
      }
    }
}

trait UniqueIndexApi {
  Self: Singleton =>

  import UniqueIndexApi._

  sealed trait ApiAsset {
    implicit val Api: Self.type = Self
  }

  type EntityIdType
  type ValueType
  def entityIdToString: StringRepresentable[EntityIdType]
  def valueTypeToString: StringRepresentable[ValueType]

  sealed trait ClientIndexState extends ApiAsset
  case class AcquisitionPendingClientState() extends ClientIndexState
  case class ReleasePendingClientState() extends ClientIndexState
  case class AcquiredClientState() extends ClientIndexState

  type ClientEventType >: ClientEvent <: ClientEvent
  // cannot be trait otherwise due to scala bug pattern matching does not work
  sealed abstract class ClientEvent extends Event with ApiAsset {
    def value: ValueType
    def reflect: Api.ClientEvent = this
  }
  case class AcquisitionStartedClientEvent(value: ValueType) extends ClientEvent
  case class AcquisitionCompletedClientEvent(value: ValueType) extends ClientEvent
  case class AcquisitionAbortedClientEvent(value: ValueType) extends ClientEvent
  case class ReleaseStartedClientEvent(value: ValueType) extends ClientEvent
  case class ReleaseCompletedClientEvent(value: ValueType) extends ClientEvent
  case class ReleaseAbortedClientEvent(value: ValueType) extends ClientEvent

  case class ClientIndexesState(map: Map[ValueType, ClientIndexState] = Map.empty) extends ApiAsset {
    def reflect: Api.ClientIndexesState = this
    def get(v: ValueType): Option[ClientIndexState] = map.get(v)
    def +(kv: (ValueType, ClientIndexState)): ClientIndexesState = ClientIndexesState(map + kv)
    def -(k: ValueType): ClientIndexesState = ClientIndexesState(map - k)
    def contains(k: ValueType): Boolean = map.contains(k)
  }

  type IndexApiType[T] >: IndexApi[T] <: IndexApi[T]
  sealed trait IndexApi[T]
  case class Acquire(value: ValueType) extends IndexApi[Unit]
  case class Release(value: ValueType) extends IndexApi[Unit]
  def castIndexApi[T](v: IndexApiType[T]): IndexApi[T] = v

  sealed abstract class Error extends ResponseError with ApiAsset {
    def reflect: Api.Error = this
  }
  case class DuplicateIndex(occupyingKey: EntityIdType, value: ValueType) extends Error
  sealed trait BadRequest extends Error
  case class IndexIsFree(key: EntityIdType, value: ValueType) extends BadRequest
  case class IndexIsAcquired(key: EntityIdType, value: ValueType) extends BadRequest
  case class EntityIdMismatch(occupyingKey: EntityIdType, requestedKey: EntityIdType, value: ValueType) extends BadRequest

  sealed abstract class UniqueIndexRequest[T] extends Request[T] with ApiAsset {
    def value: ValueType
    def reflect: Api.UniqueIndexRequest[T] = this
  }
  sealed abstract class UniqueIndexQuery[T] extends UniqueIndexRequest[T] with Query[T]
  case class GetEntityId(value: ValueType) extends UniqueIndexQuery[Option[EntityIdType]]
  sealed abstract class UniqueIndexCommand[T] extends UniqueIndexRequest[T] with Command[T]
  case class StartAcquisition(key: EntityIdType, value: ValueType) extends UniqueIndexCommand[Unit]
  case class CommitAcquisition(key: EntityIdType, value: ValueType) extends UniqueIndexCommand[Unit]
  case class RollbackAcquisition(key: EntityIdType, value: ValueType) extends UniqueIndexCommand[Unit]
  case class StartRelease(key: EntityIdType, value: ValueType) extends UniqueIndexCommand[Unit]
  case class CommitRelease(key: EntityIdType, value: ValueType) extends UniqueIndexCommand[Unit]
  case class RollbackRelease(key: EntityIdType, value: ValueType) extends UniqueIndexCommand[Unit]

  trait QueryApi[Alg[A] <: CopK[_, A], Program[_]] {
    this: ApiHelper[UniqueIndexQuery, Alg, Program] =>
    def getEntityId(value: ValueType): Program[Option[EntityIdType]] = GetEntityId(value)
  }

  trait UpdateApi[Alg[A] <: CopK[_, A], Program[_]] {
    this: ApiHelper[IndexApiType, Alg, Program] =>
    def acquire(value: ValueType): Program[Unit] = Acquire(value)
    def release(value: ValueType): Program[Unit] = Release(value)
  }

  type ClientQueryType[T] >: ClientQuery[T] <: ClientQuery[T]
  // cannot be trait otherwise due to scala bug pattern matching does not work
  sealed abstract class ClientQuery[T] extends Query[T] with ApiAsset {
    def key: EntityIdType
    def reflect: Api.ClientQuery[T] = this
  }
  case class IsIndexNeeded(key: EntityIdType, value: ValueType) extends ClientQuery[IsIndexNeededResponse]

  trait ClientApi[Alg[A] <: CopK[_, A], Program[_]] {
    this: ApiHelper[ClientQuery, Alg, Program] =>
    def isIndexNeeded(key: EntityIdType, value: ValueType): Program[IsIndexNeededResponse] = IsIndexNeeded(key, value)
  }

  def clientQueryRuntimeInject[Alg[A] <: CopK[_, A]](implicit I: CopK.Inject[ClientQuery, Alg]): UniqueIndexApi#ClientQuery ~> Lambda[a => Option[Alg[a]]] =
    Lambda[UniqueIndexApi#ClientQuery ~> Lambda[a => Option[Alg[a]]]] {
      case c: ClientQuery[_] => Some(I(c))
      case _ => None
    }

  def clientEventRuntimeInject[Alg <: Cop[_]](event: UniqueIndexApi#ClientEvent)(implicit I: Cop.Inject[ClientEvent, Alg]): Option[Alg] = event match {
    case e: ClientEvent => Some(I(e))
    case _ => None
  }

  sealed abstract class ServerEvent extends Event with ApiAsset {
    def reflect: Api.ServerEvent = this
  }
  case class AcquisitionStartedServerEvent(key: EntityIdType) extends ServerEvent
  case class AcquisitionCompletedServerEvent() extends ServerEvent
  case class ReleaseStartedServerEvent() extends ServerEvent
  case class ReleaseCompletedServerEvent() extends ServerEvent

  sealed abstract class UniqueIndexServerState extends PersistentState with ApiAsset {
    override type EventType = ServerEvent
    def reflect: Api.UniqueIndexServerState = this
  }
  case class FreeServerState() extends UniqueIndexServerState
  case class UnconfirmedServerState(key: EntityIdType) extends UniqueIndexServerState
  case class AcquiredServerState(key: EntityIdType) extends UniqueIndexServerState

  val uniqueIndexState: PersistentStateProcessor[UniqueIndexServerState] = new PersistentStateProcessor[UniqueIndexServerState] {
    override def empty: UniqueIndexServerState = FreeServerState()
    override def process(state: UniqueIndexServerState, event: ServerEvent): UniqueIndexServerState = (state, event) match {
      case (FreeServerState(),           AcquisitionStartedServerEvent(key)) => UnconfirmedServerState(key)
      case (UnconfirmedServerState(_),   AcquisitionStartedServerEvent(key)) => AcquiredServerState(key)
      case (UnconfirmedServerState(key), AcquisitionCompletedServerEvent())  => AcquiredServerState(key)
      case (UnconfirmedServerState(_),   ReleaseCompletedServerEvent())      => FreeServerState()
      case (AcquiredServerState(key),    ReleaseStartedServerEvent())        => UnconfirmedServerState(key)
      case _ => sys.error("should not happen")
    }
  }
}

trait ActorBasedUniqueIndex[A <: UniqueIndexApi] {
  def entityActor: ActorSelection
  def indexActor: ActorSelection
}
