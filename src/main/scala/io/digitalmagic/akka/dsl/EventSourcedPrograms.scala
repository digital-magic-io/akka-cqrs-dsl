package io.digitalmagic.akka.dsl

import akka.event.LoggingAdapter
import io.digitalmagic.akka.dsl.API.ResponseError
import iotaz.CopK
import scalaz._
import Scalaz._

trait EventSourcedPrograms extends EventSourced {
  import PersistentStateProcessor.ops._

  type EntityIdType

  type Events = Vector[EventType]
  type Log = Vector[LoggingAdapter => Unit]
  type Environment

  protected type QueryAlgebra[A] <: CopK[_, A]
  protected val algebraIsQuery: IsQuery[QueryAlgebra]

  protected type Index <: IndexList
  protected val clientQueryRuntimeInject: ClientQueryRuntimeInject[Index#List, Index#ClientAlgebra]
  protected val clientEventRuntimeInject: ClientEventRuntimeInject[Index#List, Index#ClientEventAlgebra]

  type Program[_]
  implicit val programMonad: Monad[Program]
  val environmentReaderMonad: MonadReader[Program, Environment]
  val eventWriterMonad: MonadTell[Program, Events]
  val stateMonad: MonadState[Program, State]
  val errorMonad: MonadError[Program, ResponseError]
  val freeMonad: MonadFree[Program, Coyoneda[QueryAlgebra, ?]]
  val indexFreeMonad: MonadFree[Program, Coyoneda[Index#Algebra, ?]]
  val logWriterMonad: MonadTell[Program, Log]

  implicit val queryAlgebraNat: QueryAlgebra ~> Program = Lambda[QueryAlgebra ~> Program] {
    q => freeMonad.liftF(Coyoneda.lift(q))
  }

  implicit val indexAlgebraNat: Index#Algebra ~> Program = Lambda[Index#Algebra ~> Program] {
    fa => indexFreeMonad.liftF(Coyoneda.lift(fa))
  }

  def pure[A](v: A): Program[A] = programMonad.pure(v)

  def ask: Program[Environment] = environmentReaderMonad.ask
  def local[A](f: Environment => Environment)(fa: Program[A]): Program[A] = environmentReaderMonad.local(f)(fa)
  def scope[A](k: Environment)(fa: Program[A]): Program[A] = environmentReaderMonad.scope(k)(fa)
  def asks[A](f: Environment => A): Program[A] = environmentReaderMonad.asks(f)

  def writer[A](w: Events, v: A): Program[A] = eventWriterMonad.writer(w, v)
  def tell(w: Events): Program[Unit] = eventWriterMonad.tell(w)

  def state[A](a: A): Program[A] = stateMonad.state(a)
  def constantState[A](a: A, s: => State): Program[A] = stateMonad.constantState(a, s)
  def init: Program[State] = stateMonad.init
  def get: Program[State] = stateMonad.get
  def gets[A](f: State => A): Program[A] = stateMonad.gets(f)
  def put(s: State): Program[Unit] = stateMonad.put(s)
  def modify(f: State => State): Program[Unit] = stateMonad.modify(f)

  def raiseError[A](e: ResponseError): Program[A] = errorMonad.raiseError(e)
  def handleError[A](fa: Program[A])(f: ResponseError => Program[A]): Program[A] = errorMonad.handleError(fa)(f)

  def log(f: LoggingAdapter => Unit): Program[Unit] = logWriterMonad.tell(Vector(f))

  def emit(e: Environment => EventType): Program[Unit] = for {
    ev <- asks(e)
    _  <- modify(_.process(ev))
    _  <- tell(Vector(ev))
  } yield ()
}