package io.digitalmagic.akka.dsl

import akka.actor.{ActorRef, Props}
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import akka.cluster.sharding.{ClusterSharding, ClusterShardingSettings}
import akka.fixes.ClusterShardingExts.implicits._
import io.digitalmagic.coproduct.TListK.:::
import io.digitalmagic.coproduct.{Cop, CopK, TNilK}
import io.digitalmagic.akka.dsl.API._
import io.digitalmagic.akka.dsl.EventSourcedActorWithInterpreter.IndexFuture
import scalaz._
import scalaz.Scalaz._

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

  override type TransientState = Unit
  override lazy val initialTransientState: TransientState = ()

  override type QueryAlgebra[A] = CopK[ClientQuery ::: TNilK, A]
  override val algebraIsQuery: IsQuery[QueryAlgebra] = implicitly

  override type Index = EmptyIndexList
  override val clientRuntime: ClientRuntime[Index#List, Index] = implicitly

  val clientApi = new ClientApi[Program]

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
            _ <- emit(ReleaseCompletedServerEvent())
          } yield None
          case IsIndexNeededResponse.Unknown => pure(None)
          case IsIndexNeededResponse.Yes => for {
            _ <- emit(AcquisitionCompletedServerEvent())
          } yield Some(entityId)
        }
      case AcquiredServerState(occupyingEntityId) => pure(Some(occupyingEntityId))
    }
  } yield result

  def startAcquisition(entityId: indexApi.EntityIdType, key: KeyType): Program[Unit] = for {
    _     <- log(_.debug(s"starting [$key] acquisition for [$entityId]"))
    state <- get
    _     <- state match {
      case FreeServerState() => emit(AcquisitionStartedServerEvent(entityId))
      case UnconfirmedServerState(occupyingEntityId) if entityId == occupyingEntityId => pure(())
      case UnconfirmedServerState(occupyingEntityId) if entityId != occupyingEntityId =>
        askAndLog[Unit](occupyingEntityId, key) {
          case IsIndexNeededResponse.No => emit(AcquisitionStartedServerEvent(entityId))
          case IsIndexNeededResponse.Unknown => raiseError[Unit](DuplicateIndex(occupyingEntityId, key))
          case IsIndexNeededResponse.Yes => emit(AcquisitionCompletedServerEvent()) >> raiseError(DuplicateIndex(occupyingEntityId, key))
        }
      case AcquiredServerState(occupyingEntityId) => raiseError(DuplicateIndex(occupyingEntityId, key))
    }
  } yield ()

  def commitAcquisition(entityId: indexApi.EntityIdType): Program[Unit] = for {
    _     <- log(_.debug(s"committing [${this.entityId}] acquisition for [$entityId]"))
    state <- get
    _     <- state match {
      case FreeServerState() => raiseError(IndexIsFree(entityId, indexKey))
      case UnconfirmedServerState(occupyingEntityId) if entityId == occupyingEntityId => emit(AcquisitionCompletedServerEvent())
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
      case UnconfirmedServerState(occupyingEntityId) if entityId == occupyingEntityId => emit(ReleaseCompletedServerEvent())
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
      case AcquiredServerState(occupyingEntityId) if entityId == occupyingEntityId => emit(ReleaseStartedServerEvent())
      case AcquiredServerState(occupyingEntityId) if entityId != occupyingEntityId => raiseError(EntityIdMismatch(occupyingEntityId, entityId, indexKey))
    }
  } yield ()

  def commitRelease(entityId: indexApi.EntityIdType): Program[Unit] = for {
    _     <- log(_.debug(s"committing [${this.entityId}] release for [$entityId]"))
    state <- get
    _     <- state match {
      case FreeServerState() => pure(())
      case UnconfirmedServerState(occupyingEntityId) if entityId == occupyingEntityId => emit(ReleaseCompletedServerEvent())
      case UnconfirmedServerState(occupyingEntityId) if entityId != occupyingEntityId => raiseError(EntityIdMismatch(occupyingEntityId, entityId, indexKey))
      case AcquiredServerState(_) => raiseError(IndexIsAcquired(entityId, indexKey))
    }
  } yield ()

  def rollbackRelease(entityId: indexApi.EntityIdType): Program[Unit] = for {
    _     <- log(_.debug(s"rolling back [${this.entityId}] release for [$entityId]"))
    state <- get
    _     <- state match {
      case FreeServerState() => raiseError(IndexIsFree(entityId, indexKey))
      case UnconfirmedServerState(occupyingEntityId) if entityId == occupyingEntityId => emit(AcquisitionCompletedServerEvent())
      case UnconfirmedServerState(occupyingEntityId) if entityId != occupyingEntityId => raiseError(EntityIdMismatch(occupyingEntityId, entityId, indexKey))
      case AcquiredServerState(occupyingEntityId) if entityId == occupyingEntityId => pure(())
      case AcquiredServerState(occupyingEntityId) if entityId != occupyingEntityId => raiseError(EntityIdMismatch(occupyingEntityId, entityId, indexKey))
    }
  } yield ()

  def requestToProgram: ConcreteUniqueIndexRequest ~> Program  = Lambda[ConcreteUniqueIndexRequest ~> Program] {
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

  override def getEnvironment(r: Request[_]): Unit = ()

  override def processSnapshot(s: Any): Option[State] = s match {
    case x: State => Some(x)
    case _ => None
  }

  override def getProgram: Request ~> MaybeProgram = Lambda[Request ~> MaybeProgram] {
    case r: ConcreteUniqueIndexRequest[_] => Some(requestToProgram(r))
    case _ => None
  }
}

case class UniqueIndexActorDef[I <: UniqueIndexApi](indexApi: I, name: String, passivateIn: FiniteDuration = 5 seconds, numberOfShards: Int = 100)(implicit I: UniqueIndexInterface[I]) {
  def extractEntityId: ExtractEntityId = {
    case msg: indexApi.ConcreteUniqueIndexRequest[_] => (indexApi.keyToString.asString(msg.key), msg)
  }

  def extractShardId: ExtractShardId = {
    msg => (math.abs(extractEntityId(msg)._1.hashCode) % numberOfShards).toString
  }

  def props(entityId: String): Props =
    Props(UniqueIndexActor(indexApi, name, entityId, passivateIn)(I))

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

case class UniqueIndexActor[I <: UniqueIndexApi](indexApi: I, name: String, entityId: String, passivateIn: FiniteDuration = 5 seconds)(implicit I: UniqueIndexInterface[I]) extends UniqueIndexPrograms with EventSourcedActorWithInterpreter {
  import indexApi._

  context.setReceiveTimeout(passivateIn)

  implicit val indexQuery = I.clientApiInterpreter(indexApi)

  override def interpreter: QueryAlgebra ~> LazyFuture = CopK.NaturalTransformation.summon
  override def indexInterpreter: Index#Algebra ~> IndexFuture = CopK.NaturalTransformation.summon
  override def clientApiInterpreter: Index#ClientAlgebra ~> Const[Unit, *] = CopK.NaturalTransformation.summon
  override def localApiInterpreter: Index#LocalAlgebra ~> Id = CopK.NaturalTransformation.summon
  override def clientEventInterpreter: ClientEventInterpreter = Cop.Function.summon

  override val persistenceId = s"${context.system.name}.UniqueIndexActor.$name.v1.$entityId"
}
