package io.digitalmagic.akka.dsl

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import akka.fixes.ClusterShardingExts.implicits._
import io.digitalmagic.akka.dsl.API._
import iotaz.TListK.:::
import iotaz.{CopK, TNilK}
import scalaz._
import Scalaz._
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}

import scala.concurrent.duration._
import scala.reflect.ClassTag

trait UniqueIndexPrograms extends EventSourcedPrograms {
  import UniqueIndexApi._

  val indexApi: UniqueIndexApi
  import indexApi._

  override type EntityIdType = String
  override type Environment = Unit

  override type EventType = ServerEvent
  override lazy val eventTypeTag: ClassTag[ServerEvent] = implicitly

  override type State = UniqueIndexServerState
  override implicit val stateTag: ClassTag[State] = implicitly
  override lazy val persistentState: PersistentStateProcessor[State] = uniqueIndexState

  override type QueryAlgebra[A] = CopK[ClientQuery ::: TNilK, A]
  override val algebraIsQuery: IsQuery[QueryAlgebra] = implicitly

  override type Index = EmptyIndexList
  override val clientQueryRuntimeInject: ClientQueryRuntimeInject[Index#List, Index#ClientAlgebra] = implicitly
  override val clientEventRuntimeInject: ClientEventRuntimeInject[Index#List, Index#ClientEventAlgebra] = implicitly

  val clientApi = new ApiHelper[ClientQuery, QueryAlgebra, Program] with ClientApi[QueryAlgebra, Program]

  def askAndLog[U](entityId: indexApi.EntityIdType, key: KeyType)(f: IsIndexNeededResponse => Program[U]): Program[U] = for {
    _        <- log(_.debug(s"[$key] is in unconfirmed state, asking [$entityId] if it is still needed"))
    response <- clientApi.isIndexNeeded(entityId, key)
    _        <- log(_.debug(s"got response from [$entityId] about [$key]: $response"))
    result   <- f(response)
  } yield result

  def getEntityId(key: KeyType): Program[Option[indexApi.EntityIdType]] = for {
    _     <- log(_.debug(s"looking up entity id for [$key]"))
    state  <- get
    result <- state match {
      case FreeServerState() => pure(None)
      case UnconfirmedServerState(entityId) =>
        askAndLog[Option[indexApi.EntityIdType]](entityId, key) {
          case IsIndexNeededResponse.No => for {
            _ <- emit(_ => ReleaseCompletedServerEvent())
          } yield None
          case IsIndexNeededResponse.Unknown => pure(None)
          case IsIndexNeededResponse.Yes => for {
            _ <- emit(_ => AcquisitionCompletedServerEvent())
          } yield Some(entityId)
        }
      case AcquiredServerState(occupyingEntityId) => pure(Some(occupyingEntityId))
    }
  } yield result

  def startAcquisition(entityId: indexApi.EntityIdType, key: KeyType): Program[Unit] = for {
    _     <- log(_.debug(s"starting [$key] acquisition for [$entityId]"))
    state <- get
    _     <- state match {
      case FreeServerState() => emit(_ => AcquisitionStartedServerEvent(entityId))
      case UnconfirmedServerState(occupyingEntityId) if entityId == occupyingEntityId => pure(())
      case UnconfirmedServerState(occupyingEntityId) if entityId != occupyingEntityId =>
        askAndLog[Unit](occupyingEntityId, key) {
          case IsIndexNeededResponse.No => emit(_ => AcquisitionStartedServerEvent(entityId))
          case IsIndexNeededResponse.Unknown => raiseError[Unit](DuplicateIndex(occupyingEntityId, key))
          case IsIndexNeededResponse.Yes => emit(_ => AcquisitionCompletedServerEvent()) >> raiseError(DuplicateIndex(occupyingEntityId, key))
        }
      case AcquiredServerState(occupyingEntityId) => raiseError(DuplicateIndex(occupyingEntityId, key))
    }
  } yield ()

  def commitAcquisition(entityId: indexApi.EntityIdType): Program[Unit] = for {
    _     <- log(_.debug(s"committing [${this.entityId}] acquisition for [$entityId]"))
    state <- get
    _     <- state match {
      case FreeServerState() => raiseError(IndexIsFree(entityId, indexKey))
      case UnconfirmedServerState(occupyingEntityId) if entityId == occupyingEntityId => emit(_ => AcquisitionCompletedServerEvent())
      case UnconfirmedServerState(occupyingEntityId) if entityId != occupyingEntityId => raiseError(EntityIdMismatch(occupyingEntityId, entityId, indexKey))
      case AcquiredServerState(occupyingEntityId) if entityId == occupyingEntityId => pure(())
      case AcquiredServerState(occupyingEntityId) if entityId != occupyingEntityId => raiseError(EntityIdMismatch(occupyingEntityId, entityId, indexKey))
    }
  } yield ()

  def rollbackAcquisition(entityId: indexApi.EntityIdType): Program[Unit] = for {
    _     <- log(_.debug(s"rolling back [${this.entityId}] acquisition for [$entityId]"))
    state <- get
    _     <- state match {
      case FreeServerState() => pure(())
      case UnconfirmedServerState(occupyingEntityId) if entityId == occupyingEntityId => emit(_ => ReleaseCompletedServerEvent())
      case UnconfirmedServerState(occupyingEntityId) if entityId != occupyingEntityId => raiseError(EntityIdMismatch(occupyingEntityId, entityId, indexKey))
      case AcquiredServerState(occupyingEntityId) if entityId == occupyingEntityId => raiseError(IndexIsAcquired(entityId, indexKey))
      case AcquiredServerState(occupyingEntityId) if entityId != occupyingEntityId => raiseError(EntityIdMismatch(occupyingEntityId, entityId, indexKey))
    }
  } yield ()

  def startRelease(entityId: indexApi.EntityIdType): Program[Unit] = for {
    _     <- log(_.debug(s"starting [${this.entityId}] release for [$entityId]"))
    state <- get
    _     <- state match {
      case FreeServerState() => raiseError(IndexIsFree(entityId, indexKey))
      case UnconfirmedServerState(occupyingEntityId) if entityId == occupyingEntityId => pure(())
      case UnconfirmedServerState(occupyingEntityId) if entityId != occupyingEntityId => raiseError(EntityIdMismatch(occupyingEntityId, entityId, indexKey))
      case AcquiredServerState(occupyingEntityId) if entityId == occupyingEntityId => emit(_ => ReleaseStartedServerEvent())
      case AcquiredServerState(occupyingEntityId) if entityId != occupyingEntityId => raiseError(EntityIdMismatch(occupyingEntityId, entityId, indexKey))
    }
  } yield ()

  def commitRelease(entityId: indexApi.EntityIdType): Program[Unit] = for {
    _     <- log(_.debug(s"committing [${this.entityId}] release for [$entityId]"))
    state <- get
    _     <- state match {
      case FreeServerState() => pure(())
      case UnconfirmedServerState(occupyingEntityId) if entityId == occupyingEntityId => emit(_ => ReleaseCompletedServerEvent())
      case UnconfirmedServerState(occupyingEntityId) if entityId != occupyingEntityId => raiseError(EntityIdMismatch(occupyingEntityId, entityId, indexKey))
      case AcquiredServerState(_) => raiseError(IndexIsAcquired(entityId, indexKey))
    }
  } yield ()

  def rollbackRelease(entityId: indexApi.EntityIdType): Program[Unit] = for {
    _     <- log(_.debug(s"rolling back [${this.entityId}] release for [$entityId]"))
    state <- get
    _     <- state match {
      case FreeServerState() => raiseError(IndexIsFree(entityId, indexKey))
      case UnconfirmedServerState(occupyingEntityId) if entityId == occupyingEntityId => emit(_ => AcquisitionCompletedServerEvent())
      case UnconfirmedServerState(occupyingEntityId) if entityId != occupyingEntityId => raiseError(EntityIdMismatch(occupyingEntityId, entityId, indexKey))
      case AcquiredServerState(occupyingEntityId) if entityId == occupyingEntityId => pure(())
      case AcquiredServerState(occupyingEntityId) if entityId != occupyingEntityId => raiseError(EntityIdMismatch(occupyingEntityId, entityId, indexKey))
    }
  } yield ()

  def requestToProgram: UniqueIndexRequest ~> Program  = Lambda[UniqueIndexRequest ~> Program] {
    case GetEntityId(key) => getEntityId(key)
    case StartAcquisition(entityId, key) => startAcquisition(entityId, key)
    case CommitAcquisition(entityId, _) => commitAcquisition(entityId)
    case RollbackAcquisition(entityId, _) => rollbackAcquisition(entityId)
    case StartRelease(entityId, _) => startRelease(entityId)
    case CommitRelease(entityId, _) => commitRelease(entityId)
    case RollbackRelease(entityId, _) => rollbackRelease(entityId)
  }

  def entityId: String
  def indexKey: KeyType = keyToString.fromString(entityId).get
}

case class UniqueIndexActorDef[A <: UniqueIndexApi](indexApi: A, name: String, passivateIn: FiniteDuration = 5 seconds, numberOfShards: Int = 100)(implicit A: ActorBasedUniqueIndex[A]) {
  def extractEntityId: ExtractEntityId = {
    case msg: indexApi.UniqueIndexRequest[_] => (indexApi.keyToString.asString(msg.key), msg)
  }

  def extractShardId: ExtractShardId = {
    msg => (math.abs(extractEntityId(msg)._1.hashCode) % numberOfShards).toString
  }

  def props(entityId: String): Props =
    Props(new UniqueIndexActor[A](indexApi, name, entityId, passivateIn)(A))

  def start(clusterSharding: ClusterSharding, settings: ClusterShardingSettings): ActorRef = {
    clusterSharding.startProperly(
      typeName = name,
      entityIdToEntityProps = props,
      settings = settings,
      extractEntityId = extractEntityId,
      extractShardId = extractShardId,
      handOffStopMessage = EventSourcedActorWithInterpreter.Stop
    )
  }
}

case class UniqueIndexActor[I <: UniqueIndexApi](indexApi: I, name: String, entityId: String, passivateIn: FiniteDuration = 5 seconds)(A: ActorBasedUniqueIndex[I]) extends UniqueIndexPrograms with EventSourcedActorWithInterpreter {
  import indexApi._

  context.setReceiveTimeout(passivateIn)

  implicit val clientQueryInterpreter: ClientQuery ~> QueryFuture = Lambda[ClientQuery ~> QueryFuture] {
    case q: IsIndexNeeded => A.entityActor query q
  }

  override type QueryAlgebra[T] = CopK[ClientQuery ::: TNilK, T]
  override def interpreter: QueryAlgebra ~> QueryFuture = CopK.NaturalTransformation.summon[QueryAlgebra, QueryFuture]
  override def indexInterpreter: Index#Algebra ~> IndexFuture = CopK.NaturalTransformation.summon[Index#Algebra, IndexFuture]
  override def clientApiInterpreter: Index#ClientAlgebra ~> Const[Unit, ?] = CopK.NaturalTransformation.summon[Index#ClientAlgebra, Const[Unit, ?]]
  override def clientEventInterpreter: ClientEventInterpreter = implicitly

  override val persistenceId = s"${context.system.name}.UniqueIndexActor.$name.v1.$entityId"
  override def getEnvironment(r: Request[_]): Unit = ()

  override def processState(s: Any): Option[State] = s match {
    case x: State => Some(x)
    case _ => None
  }

  override def getProgram: Request ~> MaybeProgram = Lambda[Request ~> MaybeProgram] {
    case r: UniqueIndexRequest[_] => Some(requestToProgram(r))
    case _ => None
  }
}
