package io.digitalmagic.akka.dsl

import java.time.Instant
import akka.actor.{ActorSelection, Props}
import io.digitalmagic.coproduct.{Cop, CopK, TNilK}
import io.digitalmagic.akka.dsl.API._
import io.digitalmagic.akka.dsl.EventSourcedActorWithInterpreter.IndexFuture
import io.digitalmagic.akka.dsl.context.ProgramContextOps
import scalaz._
import scalaz.Scalaz._

import scala.reflect.ClassTag

object Actor1 {
  sealed trait Query[A] extends API.Query[A]
  case object GetValue extends Query[Int]

  case class SetValue(value: Int) extends Command[Unit]

  class Api[Program[_]](implicit N: Query ~> Program) {
    def getValue: Program[Int] = N(GetValue)
  }

  def interpreter(actorSelection: ActorSelection): Query ~> LazyFuture = Lambda[Query ~> LazyFuture] {
    case q: GetValue.type => actorSelection query q
  }

  sealed trait Actor1Event extends Event
  case class ValueSet(value: Int) extends Actor1Event {
    override type TimestampType = Instant
    override var timestamp: Instant = Instant.now
  }

  case class Actor1State(value: Int) extends PersistentState {
    override type EventType = Actor1Event
  }

  def props: Props = Props(new Actor1)
}

trait Actor1Programs extends EventSourcedPrograms {

  import Actor1._

  override type Environment = Unit
  override val contextOps: ProgramContextOps = new ProgramContextOps

  override type EventType = Actor1Event
  override lazy val eventTypeTag: ClassTag[Actor1Event] = implicitly

  override type State = Actor1State
  override lazy val stateTag: ClassTag[State] = implicitly
  override lazy val persistentState: PersistentStateProcessor[State] = new PersistentStateProcessor[State] {
    override def empty: State = Actor1State(0)
    override def process(state: State, event: EventType): State = event match {
      case ValueSet(value) => state.copy(value = value)
    }
  }

  override type TransientState = Unit
  override lazy val initialTransientState: TransientState = ()

  override type EntityIdType = Unit

  override type QueryList = TNilK
  override type QueryAlgebra[A] = CopK[QueryList, A]
  override val algebraIsQuery: IsQuery[QueryAlgebra] = implicitly

  override type Index = EmptyIndexList
  override val clientRuntime: ClientRuntime[Index#List, Index] = implicitly

  def getValue: Program[Int] = gets(_.value)
  def setValue(value: Int): Program[Unit] = for {
    _ <- log(_.info("setting value"))
    _ <- emit(ValueSet(value))
  } yield ()

  override def getEnvironment(r: Request[_]): Unit = ()

  override def processSnapshot(s: Any): Option[State] = s match {
    case x: State => Some(x)
    case _ => None
  }

  override def getProgram: Request ~> MaybeProgram = Lambda[Request ~> MaybeProgram] {
    case GetValue => Some(getValue)
    case SetValue(value) => Some(setValue(value))
    case _ => None
  }
}

class Actor1 extends Actor1Programs with EventSourcedActorWithInterpreter {
  override def entityId: Unit = ()
  override def persistenceId: String = s"${context.system.name}.Actor1"

  override def interpreter: QueryAlgebra ~> LazyFuture = CopK.NaturalTransformation.summon
  override def indexInterpreter: Index#Algebra ~> IndexFuture = CopK.NaturalTransformation.summon
  override def clientApiInterpreter: Index#ClientAlgebra ~> Const[Unit, *] = CopK.NaturalTransformation.summon
  override def localApiInterpreter: Index#LocalAlgebra ~> Id = CopK.NaturalTransformation.summon
  override def clientEventInterpreter: ClientEventInterpreter = Cop.Function.summon
}
