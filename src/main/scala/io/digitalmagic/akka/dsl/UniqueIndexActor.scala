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

  def askAndLog[U](key: indexApi.EntityIdType, value: ValueType)(f: IsIndexNeededResponse => Program[U]): Program[U] = for {
    _        <- log(_.debug(s"[$value] is in unconfirmed state, asking [$key] if it is still needed"))
    response <- clientApi.isIndexNeeded(key, value)
    _        <- log(_.debug(s"got response from [$key] about [$value]: $response"))
    result   <- f(response)
  } yield result

  def getEntityId(value: ValueType): Program[Option[indexApi.EntityIdType]] = for {
    _     <- log(_.debug(s"looking up entity id for [$value]"))
    state  <- get
    result <- state match {
      case FreeServerState => pure(None)
      case UnconfirmedServerState(key) =>
        askAndLog[Option[indexApi.EntityIdType]](key, value) {
          case IsIndexNeededResponse.No => for {
            _ <- emit(_ => ReleaseCompletedServerEvent())
          } yield None
          case IsIndexNeededResponse.Unknown => pure(None)
          case IsIndexNeededResponse.Yes => for {
            _ <- emit(_ => AcquisitionCompletedServerEvent())
          } yield Some(key)
        }
      case AcquiredServerState(occupyingEntityId) => pure(Some(occupyingEntityId))
    }
  } yield result

  def startAcquisition(key: indexApi.EntityIdType, value: ValueType): Program[Unit] = for {
    _     <- log(_.debug(s"starting [$value] acquisition for [$key]"))
    state <- get
    _     <- state match {
      case FreeServerState => emit(_ => AcquisitionStartedServerEvent(key))
      case UnconfirmedServerState(occupyingKey) if key == occupyingKey => pure(())
      case UnconfirmedServerState(occupyingKey) if key != occupyingKey =>
        askAndLog[Unit](occupyingKey, value) {
          case IsIndexNeededResponse.No => emit(_ => AcquisitionStartedServerEvent(key))
          case IsIndexNeededResponse.Unknown => raiseError[Unit](DuplicateIndex(occupyingKey, value))
          case IsIndexNeededResponse.Yes => emit(_ => AcquisitionCompletedServerEvent()) >> raiseError(DuplicateIndex(occupyingKey, value))
        }
      case AcquiredServerState(occupyingKey) => raiseError(DuplicateIndex(occupyingKey, value))
    }
  } yield ()

  def commitAcquisition(key: indexApi.EntityIdType): Program[Unit] = for {
    _     <- log(_.debug(s"committing [$entityId] acquisition for [$key]"))
    state <- get
    _     <- state match {
      case FreeServerState => raiseError(IndexIsFree(key, indexValue))
      case UnconfirmedServerState(occupyingKey) if key == occupyingKey => emit(_ => AcquisitionCompletedServerEvent())
      case UnconfirmedServerState(occupyingKey) if key != occupyingKey => raiseError(EntityIdMismatch(occupyingKey, key, indexValue))
      case AcquiredServerState(occupyingKey) if key == occupyingKey => pure(())
      case AcquiredServerState(occupyingKey) if key != occupyingKey => raiseError(EntityIdMismatch(occupyingKey, key, indexValue))
    }
  } yield ()

  def rollbackAcquisition(key: indexApi.EntityIdType): Program[Unit] = for {
    _     <- log(_.debug(s"rolling back [$entityId] acquisition for [$key]"))
    state <- get
    _     <- state match {
      case FreeServerState => pure(())
      case UnconfirmedServerState(occupyingKey) if key == occupyingKey => emit(_ => ReleaseCompletedServerEvent())
      case UnconfirmedServerState(occupyingKey) if key != occupyingKey => raiseError(EntityIdMismatch(occupyingKey, key, indexValue))
      case AcquiredServerState(occupyingKey) if key == occupyingKey => raiseError(IndexIsAcquired(key, indexValue))
      case AcquiredServerState(occupyingKey) if key != occupyingKey => raiseError(EntityIdMismatch(occupyingKey, key, indexValue))
    }
  } yield ()

  def startRelease(key: indexApi.EntityIdType): Program[Unit] = for {
    _     <- log(_.debug(s"starting [$entityId] release for [$key]"))
    state <- get
    _     <- state match {
      case FreeServerState => raiseError(IndexIsFree(key, indexValue))
      case UnconfirmedServerState(occupyingKey) if key == occupyingKey => pure(())
      case UnconfirmedServerState(occupyingKey) if key != occupyingKey => raiseError(EntityIdMismatch(occupyingKey, key, indexValue))
      case AcquiredServerState(occupyingKey) if key == occupyingKey => emit(_ => ReleaseStartedServerEvent())
      case AcquiredServerState(occupyingKey) if key != occupyingKey => raiseError(EntityIdMismatch(occupyingKey, key, indexValue))
    }
  } yield ()

  def commitRelease(key: indexApi.EntityIdType): Program[Unit] = for {
    _     <- log(_.debug(s"committing [$entityId] release for [$key]"))
    state <- get
    _     <- state match {
      case FreeServerState => pure(())
      case UnconfirmedServerState(occupyingKey) if key == occupyingKey => emit(_ => ReleaseCompletedServerEvent())
      case UnconfirmedServerState(occupyingKey) if key != occupyingKey => raiseError(EntityIdMismatch(occupyingKey, key, indexValue))
      case AcquiredServerState(_) => raiseError(IndexIsAcquired(key, indexValue))
    }
  } yield ()

  def rollbackRelease(key: indexApi.EntityIdType): Program[Unit] = for {
    _     <- log(_.debug(s"rolling back [$entityId] release for [$key]"))
    state <- get
    _     <- state match {
      case FreeServerState => raiseError(IndexIsFree(key, indexValue))
      case UnconfirmedServerState(occupyingKey) if key == occupyingKey => emit(_ => AcquisitionCompletedServerEvent())
      case UnconfirmedServerState(occupyingKey) if key != occupyingKey => raiseError(EntityIdMismatch(occupyingKey, key, indexValue))
      case AcquiredServerState(occupyingKey) if key == occupyingKey => pure(())
      case AcquiredServerState(occupyingKey) if key != occupyingKey => raiseError(EntityIdMismatch(occupyingKey, key, indexValue))
    }
  } yield ()

  def requestToProgram: UniqueIndexRequest ~> Program  = Lambda[UniqueIndexRequest ~> Program] {
    case GetEntityId(value) => getEntityId(value)
    case StartAcquisition(key, value) => startAcquisition(key, value)
    case CommitAcquisition(key, _) => commitAcquisition(key)
    case RollbackAcquisition(key, _) => rollbackAcquisition(key)
    case StartRelease(key, _) => startRelease(key)
    case CommitRelease(key, _) => commitRelease(key)
    case RollbackRelease(key, _) => rollbackRelease(key)
  }

  def entityId: String
  def indexValue: ValueType = valueTypeToString.fromString(entityId).get
}

case class UniqueIndexActorDef[A <: UniqueIndexApi](indexApi: A, name: String, passivateIn: FiniteDuration = 5 seconds, numberOfShards: Int = 100)(implicit A: ActorBasedUniqueIndex[A]) {
  def extractEntityId: ExtractEntityId = {
    case msg: indexApi.UniqueIndexRequest[_] => (indexApi.valueTypeToString.asString(msg.value), msg)
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
