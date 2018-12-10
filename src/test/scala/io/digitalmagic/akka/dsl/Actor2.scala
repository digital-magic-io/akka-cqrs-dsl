package io.digitalmagic.akka.dsl

import akka.actor.{ActorSelection, Props}
import io.digitalmagic.akka.dsl.API._
import iotaz.{Cop, CopK, TNilK}
import scalaz._
import Scalaz._

import scala.reflect.ClassTag

object Actor2 {
  sealed trait Query[A] extends API.Query[A]
  case object GetValue extends Query[Int]

  case class SetValue(value: Int) extends Command[Unit]

  trait Api[Alg[A] <: CopK[_, A], Program[_]] {
    this: ApiHelper[Query, Alg, Program] =>
    def getValue: Program[Int] = GetValue
  }

  def interpreter(actorSelection: ActorSelection): Query ~> RequestFuture = Lambda[Query ~> RequestFuture] {
    case q: GetValue.type => actorSelection query q
  }

  sealed trait Actor2Event extends Event
  case class ValueSet(value: Int) extends Actor2Event

  case class Actor2State(value: Int) extends PersistentState {
    override type EventType = Actor2Event
  }

  def props: Props = Props(new Actor2)
}

trait Actor2Programs extends EventSourcedPrograms {

  import Actor2._

  override type Environment = Unit

  override type EventType = Actor2Event
  override lazy val eventTypeTag: ClassTag[Actor2Event] = implicitly

  override type State = Actor2State
  override lazy val stateTag: ClassTag[State] = implicitly
  override lazy val persistentState: PersistentStateProcessor[State] = new PersistentStateProcessor[State] {
    override def empty: State = Actor2State(0)
    override def process(state: State, event: EventType): State = event match {
      case ValueSet(value) => state.copy(value = value)
    }
  }

  override type EntityIdType = Unit

  override type QueryAlgebra[A] = CopK[TNilK, A]
  override val algebraIsQuery: IsQuery[QueryAlgebra] = implicitly

  override type Index = EmptyIndexList
  override val clientRuntime: ClientRuntime[Index#List, Index] = implicitly

  def getValue: Program[Int] = gets(_.value)
  def setValue(value: Int): Program[Unit] = for {
    _ <- log(_.info("setting value"))
    _ <- emit(ValueSet(value))
  } yield ()
}

class Actor2 extends Actor2Programs with EventSourcedActorWithInterpreter {
  import Actor2._


  override def entityId: Unit = ()
  override def persistenceId: String = s"${context.system.name}.Actor1"

  override def interpreter: QueryAlgebra ~> RequestFuture = CopK.NaturalTransformation.summon[QueryAlgebra, RequestFuture]
  override def indexInterpreter: Index#Algebra ~> IndexFuture = CopK.NaturalTransformation.summon[Index#Algebra, IndexFuture]
  override def clientApiInterpreter: Index#ClientAlgebra ~> Const[Unit, ?] = CopK.NaturalTransformation.summon[Index#ClientAlgebra, Const[Unit, ?]]
  override def clientEventInterpreter: ClientEventInterpreter = implicitly

  override def getEnvironment(r: Request[_]): Unit = ()

  override def processState(s: Any): Option[State] = s match {
    case x: State => Some(x)
    case _ => None
  }

  override def getProgram: Request ~> MaybeProgram = Lambda[Request ~> MaybeProgram] {
    case GetValue => Some(getValue)
    case SetValue(value) => Some(setValue(value))
    case _ => None
  }
}