package io.digitalmagic.akka.dsl

import akka.actor.Props
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import io.digitalmagic.coproduct.TListK.:::
import io.digitalmagic.coproduct.{Cop, CopK, TCons, TNil, TNilK}
import io.digitalmagic.akka.dsl
import io.digitalmagic.akka.dsl.API._
import io.digitalmagic.akka.dsl.EventSourcedActorWithInterpreter.IndexFuture
import io.digitalmagic.akka.dsl.context.ProgramContextOps
import scalaz._
import scalaz.Scalaz._

import scala.concurrent.duration._
import scala.reflect.ClassTag

object IndexExample {

  trait MyEvent extends Event

  case object MyState extends PersistentState {
    override type EventType = MyEvent
  }

  sealed trait Action
  case class AcquireAction(key: String) extends Action
  case class ReleaseAction(key: String) extends Action
  case object FailAction extends Action

  case class AcquireCommand(entityId: String, fail: Boolean) extends Command[Unit]
  case class ReleaseCommand(entityId: String) extends Command[Unit]
  case class GenericCommand(entityId: String, actions: List[Action]) extends Command[Unit]

  case class AcquireBatchCommand(entityId: String) extends Command[Unit]
  case class ReleaseBatchCommand(entityId: String) extends Command[Unit]
  case class GetBatchQuery(entityId: String) extends Query[Map[String, String]]

  @SerialVersionUID(1)
  implicit case object index1Api extends UniqueIndexApi.Base[String, String]
  @SerialVersionUID(1)
  implicit case object index2Api extends UniqueIndexApi.Base[String, String]
}

trait IndexExample extends EventSourcedPrograms {

  import IndexExample._

  override type EntityIdType = String

  override type Environment = Unit
  override val contextOps: ProgramContextOps = new ProgramContextOps

  override type QueryList = index1Api.UniqueIndexQuery ::: TNilK
  override type QueryAlgebra[A] = CopK[QueryList, A]
  override val algebraIsQuery: IsQuery[QueryAlgebra] = implicitly

  type Index = EmptyIndexList# + [index1Api.type]# + [index2Api.type]
  override val clientRuntime: ClientRuntime[Index#List, Index] = implicitly

  override type EventType = MyEvent
  override lazy val eventTypeTag: ClassTag[EventType] = implicitly
  override type State = MyState.type
  override lazy val stateTag: ClassTag[State] = implicitly
  override lazy val persistentState: PersistentStateProcessor[State] = new PersistentStateProcessor[State] {
    override def empty: State = MyState
    override def process(state: State, event: EventType): State = state
  }

  override type TransientState = Unit
  override lazy val initialTransientState: TransientState = ()

  val a1 = new index1Api.IndexUpdateApi[Program]
  val al1 = new index1Api.IndexLocalQueryApi[Program]
  val aq1 = new index1Api.UniqueIndexQueryApi[Program]
  val a2 = new index2Api.IndexUpdateApi[Program]

  def acquire(fail: Boolean): Program[Unit] = for {
    _  <- a1.acquire("abc")
    _  <- a2.acquire("abc")
    _  <- a1.acquire("def")
    _  <- a2.acquire("def")
    _  <- a1.acquire("ghi")
    _  <- { if (fail) throw new RuntimeException(); a2.acquire("ghi") }
    my <- al1.getMyEntries
    _  <- unlessM(my.contains("abc"))(raiseError(InternalError(new RuntimeException("did not contain 'abc'"))))
  } yield ()

  val release: Program[Unit] = for {
    _  <- a1.release("abc")
    _  <- a2.release("abc")
    _  <- a1.release("def")
    _  <- a2.release("def")
    _  <- a1.release("ghi")
    _  <- a2.release("ghi")
    my <- al1.getMyEntries
    _  <- whenM(my.contains("abc"))(raiseError(InternalError(new RuntimeException("did not contain 'abc'"))))
  } yield ()

  val acquireBatch: Program[Unit] = for {
    _ <- a1.acquire(Set("abc", "def"))
    _ <- a2.acquire(Set("abc", "def"))
  } yield ()

  val releaseBatch: Program[Unit] = for {
    _ <- a1.release(Set("abc", "def"))
    _ <- a2.release(Set("abc", "def"))
  } yield ()

  val getBatch: Program[Map[String, String]] = aq1.getEntityIds(Set("abc", "def"))

  def genericCommand(actions: List[Action]): Program[Unit] = for {
    _ <- actions.traverse_ {
      case AcquireAction(key) => a1.acquire(key)
      case ReleaseAction(key) => a1.release(key)
      case FailAction => raiseError[Unit](API.InternalError(new RuntimeException))
    }
  } yield ()

  override def getEnvironment(r: API.Request[_]): Environment = ()

  override def processSnapshot(s: Any): Option[State] = s match {
    case x: State => Some(x)
    case _ => None
  }

  override def getProgram: Request ~> MaybeProgram = Lambda[Request ~> MaybeProgram] {
    case AcquireCommand(_, fail) => Some(acquire(fail))
    case ReleaseCommand(_) => Some(release)
    case GenericCommand(_, actions) => Some(genericCommand(actions))
    case AcquireBatchCommand(_) => Some(acquireBatch)
    case ReleaseBatchCommand(_) => Some(releaseBatch)
    case GetBatchQuery(_) => Some(getBatch)
    case _ => None
  }
}

object IndexExampleActor {
  def extractEntityId: ExtractEntityId = {
    case msg: UniqueIndexApi#ClientQuery[_] => (msg.Api.entityIdToString.asString(msg.reflect.entityId), msg)
    case msg: IndexExample.AcquireCommand => (msg.entityId, msg)
    case msg: IndexExample.ReleaseCommand => (msg.entityId, msg)
    case msg: IndexExample.GenericCommand => (msg.entityId, msg)
    case msg: IndexExample.AcquireBatchCommand => (msg.entityId, msg)
    case msg: IndexExample.ReleaseBatchCommand => (msg.entityId, msg)
    case msg: IndexExample.GetBatchQuery => (msg.entityId, msg)
  }

  def extractShardId: ExtractShardId = {
    msg => (math.abs(extractEntityId(msg)._1.hashCode) % 100).toString
  }

  def props(name: String, entityId: String)(implicit I1: UniqueIndexInterface[IndexExample.index1Api.type], I2: UniqueIndexInterface[IndexExample.index2Api.type]) =
    Props(IndexExampleActor(name, entityId))
}

case class IndexExampleActor(name: String, entityId: String)(implicit I1: UniqueIndexInterface[IndexExample.index1Api.type], I2: UniqueIndexInterface[IndexExample.index2Api.type]) extends IndexExample with EventSourcedActorWithInterpreter {
  import IndexExample._

  context.setReceiveTimeout(1 seconds)

  implicit val index1QueryApi = I1.queryApiInterpreter(index1Api)
  override def interpreter: QueryAlgebra ~> LazyFuture = CopK.NaturalTransformation.summon
  override def indexInterpreter: Index#Algebra ~> IndexFuture = CopK.NaturalTransformation.summon
  override def clientApiInterpreter: Index#ClientAlgebra ~> Const[Unit, *] = CopK.NaturalTransformation.summon
  override def localApiInterpreter: Index#LocalAlgebra ~> Id = CopK.NaturalTransformation.summon
  override def clientEventInterpreter: ClientEventInterpreter = Cop.Function.summon

  override def persistenceId = s"${context.system.name}.IndexExample.v1.$name.$entityId"
}
