package io.digitalmagic.akka.dsl

import akka.actor.{ActorRef, ActorSelection}
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

  abstract class Base[E, K](dummy: Int, E: StringRepresentable[E], K: StringRepresentable[K]) extends UniqueIndexApi {
    Self: Singleton =>
    private[UniqueIndexApi] def this() = this(0, null, null) // we need this constructor for java serialization
    def this()(implicit E: StringRepresentable[E], K: StringRepresentable[K]) = this(0, E, K)
    type ClientQueryType[T] = ClientQuery[T]
    type KeyType = K
    type IndexApiType[T] = IndexApi[T]
    type EntityIdType = E
    type ClientEventType = ClientEvent
    override def keyToString: StringRepresentable[K] = K
    override def entityIdToString: StringRepresentable[E] = E
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
      (state.get(event.key), event) match {
        case (None,                                  AcquisitionStartedClientEvent(k))   => state + (k -> AcquisitionPendingClientState())
        case (Some(AcquisitionPendingClientState()), AcquisitionStartedClientEvent(_))   => state
        case (Some(AcquisitionPendingClientState()), AcquisitionCompletedClientEvent(k)) => state + (k -> AcquiredClientState())
        case (Some(AcquisitionPendingClientState()), AcquisitionAbortedClientEvent(k))   => state - k
        case (Some(AcquiredClientState()),           ReleaseStartedClientEvent(k))       => state + (k -> ReleasePendingClientState())
        case (Some(ReleasePendingClientState()),     ReleaseStartedClientEvent(_))       => state
        case (Some(ReleasePendingClientState()),     ReleaseCompletedClientEvent(k))     => state - k
        case (Some(ReleasePendingClientState()),     ReleaseAbortedClientEvent(k))       => state + (k -> AcquiredClientState())
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
  type KeyType
  def entityIdToString: StringRepresentable[EntityIdType]
  def keyToString: StringRepresentable[KeyType]

  sealed trait ClientIndexState extends ApiAsset
  case class AcquisitionPendingClientState() extends ClientIndexState
  case class ReleasePendingClientState() extends ClientIndexState
  case class AcquiredClientState() extends ClientIndexState

  type ClientEventType >: ClientEvent <: ClientEvent
  // cannot be trait otherwise due to scala bug pattern matching does not work
  sealed abstract class ClientEvent extends Event with ApiAsset {
    def key: KeyType
    def reflect: Api.ClientEvent = this
  }
  case class AcquisitionStartedClientEvent(key: KeyType) extends ClientEvent
  case class AcquisitionCompletedClientEvent(key: KeyType) extends ClientEvent
  case class AcquisitionAbortedClientEvent(key: KeyType) extends ClientEvent
  case class ReleaseStartedClientEvent(key: KeyType) extends ClientEvent
  case class ReleaseCompletedClientEvent(key: KeyType) extends ClientEvent
  case class ReleaseAbortedClientEvent(key: KeyType) extends ClientEvent

  case class ClientIndexesState(map: Map[KeyType, ClientIndexState] = Map.empty) extends ApiAsset {
    def reflect: Api.ClientIndexesState = this
    def get(k: KeyType): Option[ClientIndexState] = map.get(k)
    def +(kv: (KeyType, ClientIndexState)): ClientIndexesState = ClientIndexesState(map + kv)
    def -(k: KeyType): ClientIndexesState = ClientIndexesState(map - k)
    def contains(k: KeyType): Boolean = map.contains(k)
  }

  type IndexApiType[T] >: IndexApi[T] <: IndexApi[T]
  sealed trait IndexApi[T]
  case class Acquire(key: KeyType) extends IndexApi[Unit]
  case class Release(key: KeyType) extends IndexApi[Unit]
  def castIndexApi[T](v: IndexApiType[T]): IndexApi[T] = v

  sealed abstract class Error extends ResponseError with ApiAsset {
    def reflect: Api.Error = this
  }
  case class DuplicateIndex(occupyingEntityId: EntityIdType, key: KeyType) extends Error
  sealed trait BadRequest extends Error
  case class IndexIsFree(entityId: EntityIdType, key: KeyType) extends BadRequest
  case class IndexIsAcquired(entityId: EntityIdType, key: KeyType) extends BadRequest
  case class EntityIdMismatch(occupyingEntityId: EntityIdType, requestedEntityId: EntityIdType, key: KeyType) extends BadRequest

  sealed abstract class UniqueIndexRequest[T] extends Request[T] with ApiAsset {
    def key: KeyType
    def reflect: Api.UniqueIndexRequest[T] = this
  }
  sealed abstract class UniqueIndexQuery[T] extends UniqueIndexRequest[T] with Query[T]
  case class GetEntityId(key: KeyType) extends UniqueIndexQuery[Option[EntityIdType]]
  sealed abstract class UniqueIndexCommand[T] extends UniqueIndexRequest[T] with Command[T]
  case class StartAcquisition(entityId: EntityIdType, key: KeyType) extends UniqueIndexCommand[Unit]
  case class CommitAcquisition(entityId: EntityIdType, key: KeyType) extends UniqueIndexCommand[Unit]
  case class RollbackAcquisition(entityId: EntityIdType, key: KeyType) extends UniqueIndexCommand[Unit]
  case class StartRelease(entityId: EntityIdType, key: KeyType) extends UniqueIndexCommand[Unit]
  case class CommitRelease(entityId: EntityIdType, key: KeyType) extends UniqueIndexCommand[Unit]
  case class RollbackRelease(entityId: EntityIdType, key: KeyType) extends UniqueIndexCommand[Unit]

  trait QueryApi[Alg[A] <: CopK[_, A], Program[_]] {
    this: ApiHelper[UniqueIndexQuery, Alg, Program] =>
    def getEntityId(key: KeyType): Program[Option[EntityIdType]] = GetEntityId(key)
  }

  trait UpdateApi[Alg[A] <: CopK[_, A], Program[_]] {
    this: ApiHelper[IndexApiType, Alg, Program] =>
    def acquire(key: KeyType): Program[Unit] = Acquire(key)
    def release(key: KeyType): Program[Unit] = Release(key)
  }

  trait LowLevelApi {
    def startAcquisition(entityId: EntityIdType, key: KeyType): RequestFuture[Unit]
    def commitAcquisition(entityId: EntityIdType, key: KeyType): Unit
    def rollbackAcquisition(entityId: EntityIdType, key: KeyType): Unit
    def startRelease(entityId: EntityIdType, key: KeyType): RequestFuture[Unit]
    def commitRelease(entityId: EntityIdType, key: KeyType): Unit
    def rollbackRelease(entityId: EntityIdType, key: KeyType): Unit
  }

  type ClientQueryType[T] >: ClientQuery[T] <: ClientQuery[T]
  // cannot be trait otherwise due to scala bug pattern matching does not work
  sealed abstract class ClientQuery[T] extends Query[T] with ApiAsset {
    def entityId: EntityIdType
    def reflect: Api.ClientQuery[T] = this
  }
  case class IsIndexNeeded(entityId: EntityIdType, key: KeyType) extends ClientQuery[IsIndexNeededResponse]

  trait ClientApi[Alg[A] <: CopK[_, A], Program[_]] {
    this: ApiHelper[ClientQuery, Alg, Program] =>
    def isIndexNeeded(entityId: EntityIdType, key: KeyType): Program[IsIndexNeededResponse] = IsIndexNeeded(entityId, key)
  }

  def clientQueryRuntimeInject[Alg[A] <: CopK[_, A]](implicit I: CopK.Inject[ClientQueryType, Alg]): UniqueIndexApi#ClientQueryType ~> Lambda[a => Option[Alg[a]]] =
    Lambda[UniqueIndexApi#ClientQuery ~> Lambda[a => Option[Alg[a]]]] {
      case c: ClientQuery[_] => Some(I(c))
      case _ => None
    }

  def clientEventRuntimeInject[Alg <: Cop[_]](event: UniqueIndexApi#ClientEventType)(implicit I: Cop.Inject[ClientEventType, Alg]): Option[Alg] = event match {
    case e: ClientEvent => Some(I(e))
    case _ => None
  }

  sealed abstract class ServerEvent extends Event with ApiAsset {
    def reflect: Api.ServerEvent = this
  }
  case class AcquisitionStartedServerEvent(entityId: EntityIdType) extends ServerEvent
  case class AcquisitionCompletedServerEvent() extends ServerEvent
  case class ReleaseStartedServerEvent() extends ServerEvent
  case class ReleaseCompletedServerEvent() extends ServerEvent

  sealed abstract class UniqueIndexServerState extends PersistentState with ApiAsset {
    override type EventType = ServerEvent
    def reflect: Api.UniqueIndexServerState = this
  }
  case class FreeServerState() extends UniqueIndexServerState
  case class UnconfirmedServerState(entityId: EntityIdType) extends UniqueIndexServerState
  case class AcquiredServerState(entityId: EntityIdType) extends UniqueIndexServerState

  val uniqueIndexState: PersistentStateProcessor[UniqueIndexServerState] = new PersistentStateProcessor[UniqueIndexServerState] {
    override def empty: UniqueIndexServerState = FreeServerState()
    override def process(state: UniqueIndexServerState, event: ServerEvent): UniqueIndexServerState = (state, event) match {
      case (FreeServerState(),                AcquisitionStartedServerEvent(entityId)) => UnconfirmedServerState(entityId)
      case (UnconfirmedServerState(_),        AcquisitionStartedServerEvent(entityId)) => AcquiredServerState(entityId)
      case (UnconfirmedServerState(entityId), AcquisitionCompletedServerEvent())       => AcquiredServerState(entityId)
      case (UnconfirmedServerState(_),        ReleaseCompletedServerEvent())           => FreeServerState()
      case (AcquiredServerState(entityId),    ReleaseStartedServerEvent())             => UnconfirmedServerState(entityId)
      case _ => sys.error("should not happen")
    }
  }
}

trait UniqueIndexInterface[I <: UniqueIndexApi] {
  def clientApiInterpreter(api: I): api.ClientQuery ~> RequestFuture
  def lowLevelApi(api: I): api.LowLevelApi
}

case class ActorBasedUniqueIndex[I <: UniqueIndexApi](entityActor: ActorSelection, indexActor: ActorSelection) extends UniqueIndexInterface[I] {
  override def clientApiInterpreter(api: I): api.ClientQuery ~> RequestFuture = Lambda[api.ClientQuery ~> RequestFuture] {
    case q: api.IsIndexNeeded => entityActor query q
  }

  override def lowLevelApi(api: I): api.LowLevelApi = new api.LowLevelApi {
    override def startAcquisition(entityId: api.EntityIdType, key: api.KeyType): RequestFuture[Unit] = indexActor.command(api.StartAcquisition(entityId, key))
    override def commitAcquisition(entityId: api.EntityIdType, key: api.KeyType): Unit = indexActor.tell(api.CommitAcquisition(entityId, key), ActorRef.noSender)
    override def rollbackAcquisition(entityId: api.EntityIdType, key: api.KeyType): Unit = indexActor.tell(api.RollbackAcquisition(entityId, key), ActorRef.noSender)
    override def startRelease(entityId: api.EntityIdType, key: api.KeyType): RequestFuture[Unit] = indexActor.command(api.StartRelease(entityId, key))
    override def commitRelease(entityId: api.EntityIdType, key: api.KeyType): Unit = indexActor.tell(api.CommitRelease(entityId, key), ActorRef.noSender)
    override def rollbackRelease(entityId: api.EntityIdType, key: api.KeyType): Unit = indexActor.tell(api.RollbackRelease(entityId, key), ActorRef.noSender)
  }
}
