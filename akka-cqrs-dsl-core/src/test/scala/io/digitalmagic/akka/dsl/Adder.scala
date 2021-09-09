package io.digitalmagic.akka.dsl

import java.time.Instant
import akka.actor.Props
import io.digitalmagic.coproduct.TListK.:::
import io.digitalmagic.coproduct.{Cop, CopK, TNilK}
import io.digitalmagic.akka.dsl.API._
import io.digitalmagic.akka.dsl.EventSourcedActorWithInterpreter.IndexFuture
import io.digitalmagic.akka.dsl.context.ProgramContextOps
import scalaz._
import scalaz.Scalaz._

import scala.reflect.ClassTag

object Adder {
  trait MyEventType extends Event
  case object MyEvent extends MyEventType {
    override type TimestampType = Instant
    override var timestamp: Instant = Instant.now
  }

  case class MyState(n: Int = 0) extends PersistentState {
    override type EventType = MyEventType
  }

  val myStateProcessor: PersistentStateProcessor[MyState] = new PersistentStateProcessor[MyState] {
    override def empty: MyState = MyState()
    override def process(state: MyState, event: MyEventType): MyState = event match {
      case MyEvent => state.copy(n = state.n + 1)
    }
  }

  def props(implicit api1: Actor1.Query ~> LazyFuture, api2: Actor2.Query ~> LazyFuture): Props = Props(new Adder())

  case object QueryAndAdd extends Command[Int]
}

trait AdderPrograms extends EventSourcedPrograms {

  import Adder._

  override type Environment = Unit
  override val contextOps: ProgramContextOps = new ProgramContextOps

  override type EntityIdType = Unit

  override type EventType = MyEventType
  override lazy val eventTypeTag: ClassTag[MyEventType] = implicitly

  override type State = MyState
  override lazy val stateTag: ClassTag[MyState] = implicitly
  override lazy val persistentState: PersistentStateProcessor[State] = myStateProcessor

  override type TransientState = Unit
  override lazy val initialTransientState: TransientState = ()

  override type QueryList = Actor1.Query ::: Actor2.Query ::: TNilK
  override type QueryAlgebra[A] = CopK[QueryList, A]
  override val algebraIsQuery: IsQuery[QueryAlgebra] = implicitly

  override type Index = EmptyIndexList
  override val clientRuntime: ClientRuntime[Index#List, Index] = implicitly

  val a1 = new Actor1.Api[Program]
  val a2 = new Actor2.Api[Program]

  def queryAndAdd: Program[Int] = for {
    v1 <- a1.getValue
    v2 <- a2.getValue
    _  <- emit(MyEvent)
  } yield v1 + v2

  override def getEnvironment(r: Request[_]): Unit = ()

  override def processSnapshot(s: Any): Option[State] = s match {
    case x: State => Some(x)
    case _ => None
  }

  override def getProgram: Request ~> MaybeProgram = Lambda[Request ~> MaybeProgram] {
    case QueryAndAdd => Some(queryAndAdd)
    case _ => None
  }
}

class Adder()(implicit val api1: Actor1.Query ~> LazyFuture, val api2: Actor2.Query ~> LazyFuture) extends AdderPrograms with EventSourcedActorWithInterpreter {
  override def entityId: Unit = ()
  override val persistenceId: String = s"${context.system.name}.MyExampleActor"

  override def interpreter: QueryAlgebra ~> LazyFuture = CopK.NaturalTransformation.summon
  override def indexInterpreter: Index#Algebra ~> IndexFuture = CopK.NaturalTransformation.summon
  override def clientApiInterpreter: Index#ClientAlgebra ~> Const[Unit, *] = CopK.NaturalTransformation.summon
  override def localApiInterpreter: Index#LocalAlgebra ~> Id = CopK.NaturalTransformation.summon
  override def clientEventInterpreter: ClientEventInterpreter = Cop.Function.summon
}