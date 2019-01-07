package io.digitalmagic.akka.dsl

import akka.actor.Props
import akka.cluster.sharding.ShardRegion.{ExtractEntityId, ExtractShardId}
import io.digitalmagic.akka.dsl.API._
import iotaz.{CopK, TNilK}
import scalaz.Scalaz._
import scalaz._

import scala.concurrent.duration._
import scala.reflect.ClassTag

object IndexExample {

  trait MyEvent extends Event

  case object MyState extends PersistentState {
    override type EventType = MyEvent
  }

  case class AcquireCommand(entityId: String, fail: Boolean) extends Command[Unit]
  case class ReleaseCommand(entityId: String) extends Command[Unit]

  @SerialVersionUID(1)
  implicit case object index1Api extends UniqueIndexApi.Base[String, String]
  @SerialVersionUID(1)
  implicit case object index2Api extends UniqueIndexApi.Base[String, String]
}

trait IndexExample extends EventSourcedPrograms {

  import IndexExample._

  override type EntityIdType = String

  override type Environment = Unit
  override type QueryAlgebra[A] = CopK[TNilK, A]
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

  val a1 = new ApiHelper[index1Api.IndexApiType, Index#Algebra, Program] with index1Api.UpdateApi[Index#Algebra, Program]
  val a2 = new ApiHelper[index2Api.IndexApiType, Index#Algebra, Program] with index2Api.UpdateApi[Index#Algebra, Program]

  def acquire(fail: Boolean): Program[Unit] = for {
    _ <- a1.acquire("abc")
    _ <- a2.acquire("abc")
    _ <- a1.acquire("def")
    _ <- a2.acquire("def")
    _ <- a1.acquire("ghi")
    _ <- { if (fail) throw new RuntimeException(); a2.acquire("ghi") }
  } yield ()

  val release: Program[Unit] = for {
    _ <- a1.release("abc")
    _ <- a2.release("abc")
    _ <- a1.release("def")
    _ <- a2.release("def")
    _ <- a1.release("ghi")
    _ <- a2.release("ghi")
  } yield ()

  override def getEnvironment(r: API.Request[_]): Environment = ()

  override def processSnapshot(s: Any): Option[State] = s match {
    case x: State => Some(x)
    case _ => None
  }

  override def getProgram: Request ~> MaybeProgram = Lambda[Request ~> MaybeProgram] {
    case AcquireCommand(_, fail) => Some(acquire(fail))
    case ReleaseCommand(_) => Some(release)
    case _ => None
  }
}

object IndexExampleActor {
  def extractEntityId: ExtractEntityId = {
    case msg: UniqueIndexApi#ClientQuery[_] => (msg.Api.entityIdToString.asString(msg.reflect.entityId), msg)
    case msg: IndexExample.AcquireCommand => (msg.entityId, msg)
    case msg: IndexExample.ReleaseCommand => (msg.entityId, msg)
  }

  def extractShardId: ExtractShardId = {
    msg => (math.abs(extractEntityId(msg)._1.hashCode) % 100).toString
  }

  def props(entityId: String)(implicit I1: UniqueIndexInterface[IndexExample.index1Api.type], I2: UniqueIndexInterface[IndexExample.index2Api.type]) = Props(IndexExampleActor(entityId))
}

case class IndexExampleActor(entityId: String)(implicit I1: UniqueIndexInterface[IndexExample.index1Api.type], I2: UniqueIndexInterface[IndexExample.index2Api.type]) extends IndexExample with EventSourcedActorWithInterpreter {
  import IndexExample._

  context.setReceiveTimeout(1 seconds)

  override def interpreter: QueryAlgebra ~> RequestFuture = CopK.NaturalTransformation.summon[QueryAlgebra, RequestFuture]
  override def indexInterpreter: Index#Algebra ~> IndexFuture = CopK.NaturalTransformation.summon[Index#Algebra, IndexFuture]
  override def clientApiInterpreter: Index#ClientAlgebra ~> Const[Unit, ?] = CopK.NaturalTransformation.summon[Index#ClientAlgebra, Const[Unit, ?]]
  override def clientEventInterpreter: ClientEventInterpreter = implicitly

  override def persistenceId = s"${context.system.name}.IndexExample.v1.$entityId"
}