package io.digitalmagic.akka.dsl

import java.time.Instant

import akka.actor.{ActorRef, ActorSelection}
import io.digitalmagic.akka.dsl.API._
import iotaz.{Cop, CopK}
import scalaz._
import scalaz.Scalaz._

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
  type LocalApiAux[E, I <: UniqueIndexApi, T[_]] = I { type EntityIdType = E; type LocalQueryType[X] = T[X] }

  import scala.reflect.runtime.universe.Mirror
  def getApiIdFor(api: UniqueIndexApi)(implicit mirror: Mirror): String = mirror.reflect(api).symbol.fullName
  def getApiById(id: String)(implicit mirror: Mirror): UniqueIndexApi = mirror.reflectModule(mirror.staticModule(id)).instance.asInstanceOf[UniqueIndexApi]

  abstract class Base[E, K](dummy: Int, E: StringRepresentable[E], K: StringRepresentable[K]) extends UniqueIndexApi {
    Self: Singleton =>
    private[UniqueIndexApi] def this() = this(0, null, null) // we need this constructor for java serialization
    def this()(implicit E: StringRepresentable[E], K: StringRepresentable[K]) = this(0, E, K)
    type ClientQueryType[T] = ClientQuery[T]
    type LocalQueryType[T] = LocalQuery[T]
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

  trait ApiAsset {
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
    override type TimestampType = Instant
    var timestamp: Instant = Instant.now()
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
    def acquiredKeys: Set[KeyType] = map.collect {
      case (key, AcquisitionPendingClientState()) => key
      case (key, AcquiredClientState()) => key
    }.toSet
    def get(k: KeyType): Option[ClientIndexState] = map.get(k)
    def +(kv: (KeyType, ClientIndexState)): ClientIndexesState = ClientIndexesState(map + kv)
    def -(k: KeyType): ClientIndexesState = ClientIndexesState(map - k)
    def contains(k: KeyType): Boolean = map.contains(k)
  }

  type IndexApiType[T] >: IndexApi[T] <: IndexApi[T]
  sealed trait IndexApi[T]
  case class Acquire(keys: Set[KeyType]) extends IndexApi[Unit]
  case class Release(keys: Set[KeyType]) extends IndexApi[Unit]
  def castIndexApi[T](v: IndexApiType[T]): IndexApi[T] = v

  sealed abstract class Error extends ResponseError with ApiAsset {
    def reflect: Api.Error = this
  }
  case class DuplicateIndex(occupyingEntityId: EntityIdType, key: KeyType) extends Error {
    override def getMessage: String = s"${Api}.DuplicateIndex(${occupyingEntityId}, ${key})"
  }
  sealed trait BadRequest extends Error
  case class IndexIsFree(entityId: EntityIdType, key: KeyType) extends BadRequest {
    override def getMessage: String = s"${Api}.IndexIsFree(${entityId}, ${key})"
  }
  case class IndexIsAcquired(entityId: EntityIdType, key: KeyType) extends BadRequest {
    override def getMessage: String = s"${Api}.IndexIsAcquired(${entityId}, ${key})"
  }
  case class EntityIdMismatch(occupyingEntityId: EntityIdType, requestedEntityId: EntityIdType, key: KeyType) extends BadRequest {
    override def getMessage: String = s"${Api}.EntityIdMismatch(${occupyingEntityId}, ${requestedEntityId}, ${key})"
  }

  sealed abstract class UniqueIndexRequest[T] extends Request[T] with ApiAsset {
    def reflect: Api.UniqueIndexRequest[T] = this
  }
  sealed trait ConcreteUniqueIndexRequest[T] extends UniqueIndexRequest[T] {
    def key: KeyType
  }
  sealed abstract class UniqueIndexQuery[T] extends UniqueIndexRequest[T] with Query[T]
  sealed abstract class ConcreteUniqueIndexQuery[T] extends UniqueIndexQuery[T] with ConcreteUniqueIndexRequest[T] with Query[T]
  case class GetEntityId(key: KeyType) extends ConcreteUniqueIndexQuery[Option[EntityIdType]]
  case class GetEntityIds(keys: Set[KeyType]) extends UniqueIndexQuery[Map[KeyType, EntityIdType]]
  sealed abstract class UniqueIndexCommand[T] extends ConcreteUniqueIndexRequest[T] with Command[T]
  case class StartAcquisition(entityId: EntityIdType, key: KeyType) extends UniqueIndexCommand[Unit]
  case class CommitAcquisition(entityId: EntityIdType, key: KeyType) extends UniqueIndexCommand[Unit]
  case class RollbackAcquisition(entityId: EntityIdType, key: KeyType) extends UniqueIndexCommand[Unit]
  case class StartRelease(entityId: EntityIdType, key: KeyType) extends UniqueIndexCommand[Unit]
  case class CommitRelease(entityId: EntityIdType, key: KeyType) extends UniqueIndexCommand[Unit]
  case class RollbackRelease(entityId: EntityIdType, key: KeyType) extends UniqueIndexCommand[Unit]

  trait QueryApi[Alg[A] <: CopK[_, A], Program[_]] {
    this: ApiHelper[UniqueIndexQuery, Alg, Program] =>
    def getEntityId(key: KeyType): Program[Option[EntityIdType]] = GetEntityId(key)
    def getEntityIds(keys: Set[KeyType]): Program[Map[KeyType, EntityIdType]] = GetEntityIds(keys)
  }

  trait UpdateApi[Alg[A] <: CopK[_, A], Program[_]] {
    this: ApiHelper[IndexApiType, Alg, Program] =>
    def acquire(key: KeyType): Program[Unit] = Acquire(Set(key))
    def acquire(keys: Set[KeyType]): Program[Unit] = Acquire(keys)
    def release(key: KeyType): Program[Unit] = Release(Set(key))
    def release(keys: Set[KeyType]): Program[Unit] = Release(keys)
  }

  trait LowLevelApi {
    def startAcquisition(entityId: EntityIdType, key: KeyType): LazyFuture[Unit]
    def commitAcquisition(entityId: EntityIdType, key: KeyType): Unit
    def rollbackAcquisition(entityId: EntityIdType, key: KeyType): Unit
    def startRelease(entityId: EntityIdType, key: KeyType): LazyFuture[Unit]
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

  type LocalQueryType[T] >: LocalQuery[T] <: LocalQuery[T]
  sealed abstract class LocalQuery[T]
  case object GetMyEntries extends LocalQuery[Set[KeyType]]

  trait LocalApi[Alg[A] <: CopK[_, A], Program[_]] {
    this: ApiHelper[LocalQuery, Alg, Program] =>
    def getMyEntries: Program[Set[KeyType]] = GetMyEntries
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
    override type TimestampType = Instant
    var timestamp: Instant = Instant.now()
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
      case (UnconfirmedServerState(_),        AcquisitionStartedServerEvent(entityId)) => UnconfirmedServerState(entityId)
      case (UnconfirmedServerState(entityId), AcquisitionCompletedServerEvent())       => AcquiredServerState(entityId)
      case (UnconfirmedServerState(_),        ReleaseCompletedServerEvent())           => FreeServerState()
      case (AcquiredServerState(entityId),    ReleaseStartedServerEvent())             => UnconfirmedServerState(entityId)
      case _ => sys.error("should not happen")
    }
  }
}

trait IndexInterface[I <: UniqueIndexApi] {
  def clientApiInterpreter(api: I): api.ClientQuery ~> LazyFuture
  def lowLevelApi(api: I): api.LowLevelApi
}

trait UniqueIndexInterface[I <: UniqueIndexApi] extends IndexInterface[I] {
  def queryApiInterpreter(api: I): api.UniqueIndexQuery ~> LazyFuture
}

trait ActorBasedIndex[I <: UniqueIndexApi] extends IndexInterface[I] {
  val entityActor: ActorSelection
  val indexActor: ActorSelection

  override def clientApiInterpreter(api: I): api.ClientQuery ~> LazyFuture = Lambda[api.ClientQuery ~> LazyFuture] {
    case q: api.IsIndexNeeded => entityActor query q
  }

  override def lowLevelApi(api: I): api.LowLevelApi = new api.LowLevelApi {
    override def startAcquisition(entityId: api.EntityIdType, key: api.KeyType): LazyFuture[Unit] = indexActor.command(api.StartAcquisition(entityId, key))
    override def commitAcquisition(entityId: api.EntityIdType, key: api.KeyType): Unit = indexActor.tell(api.CommitAcquisition(entityId, key), ActorRef.noSender)
    override def rollbackAcquisition(entityId: api.EntityIdType, key: api.KeyType): Unit = indexActor.tell(api.RollbackAcquisition(entityId, key), ActorRef.noSender)
    override def startRelease(entityId: api.EntityIdType, key: api.KeyType): LazyFuture[Unit] = indexActor.command(api.StartRelease(entityId, key))
    override def commitRelease(entityId: api.EntityIdType, key: api.KeyType): Unit = indexActor.tell(api.CommitRelease(entityId, key), ActorRef.noSender)
    override def rollbackRelease(entityId: api.EntityIdType, key: api.KeyType): Unit = indexActor.tell(api.RollbackRelease(entityId, key), ActorRef.noSender)
  }
}

case class ActorBasedUniqueIndex[I <: UniqueIndexApi](entityActor: ActorSelection, indexActor: ActorSelection) extends ActorBasedIndex[I] with UniqueIndexInterface[I] {
  override def queryApiInterpreter(api: I): api.UniqueIndexQuery ~> LazyFuture = Lambda[api.UniqueIndexQuery ~> LazyFuture] {
    case q: api.GetEntityId => indexActor query q
    case api.GetEntityIds(keys) => keys.toList.traverse(k => indexActor.query(api.GetEntityId(k)).asFuture.map ((k, _))).map(_.collect{ case (k, Some(v)) => (k, v) }.toMap)
  }
}
