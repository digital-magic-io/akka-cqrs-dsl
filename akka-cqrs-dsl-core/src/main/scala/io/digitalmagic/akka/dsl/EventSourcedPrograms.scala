package io.digitalmagic.akka.dsl

import akka.event.LoggingAdapter
import io.digitalmagic.akka.dsl.API.{Request, ResponseError}
import iotaz.CopK
import scalaz._
import Scalaz._

trait EventSourcedPrograms extends EventSourced {
  type EntityIdType

  type Events = Vector[EventType]
  type Log = Vector[LoggingAdapter => Unit]
  type Environment

  type MaybeProgram[A] = Option[Program[A]]

  def getEnvironment(r: Request[_]): Environment
  def getProgram: Request ~> MaybeProgram
  def processSnapshot(s: Any): Option[State]

  protected type QueryAlgebra[A] <: CopK[_, A]
  protected val algebraIsQuery: IsQuery[QueryAlgebra]

  protected type Index <: IndexList
  protected val clientRuntime: ClientRuntime[Index#List, Index]

  type TransientState
  val initialTransientState: TransientState

  type Program[_]
  implicit val programMonad: Monad[Program]
  val environmentReaderMonad: MonadReader[Program, Environment]
  val eventWriterMonad: MonadTell[Program, Events]
  val stateMonad: MonadState[Program, State]
  val transientStateMonad: MonadState[Program, TransientState]
  val localIndexQueryMonad: MonadFree[Program, Coyoneda[Index#LocalAlgebra, *]]
  val errorMonad: MonadError[Program, ResponseError]
  val freeMonad: MonadFree[Program, Coyoneda[QueryAlgebra, *]]
  val indexFreeMonad: MonadFree[Program, Coyoneda[Index#Algebra, *]]
  val logWriterMonad: MonadTell[Program, Log]

  @deprecated("This is not needed any more when using new Api classes", "2.0.18")
  implicit val queryAlgebraNatDeprecated: QueryAlgebra ~> Program = Lambda[QueryAlgebra ~> Program] {
    q => freeMonad.liftF(Coyoneda.lift(q))
  }

  implicit def queryAlgebraNat[T[_]](implicit inj: CopK.Inject[T, QueryAlgebra]): T ~> Program = Lambda[T ~> Program] {
    q => freeMonad.liftF(Coyoneda.lift(inj(q)))
  }

  @deprecated("This is not needed any more when using new Api classes", "2.0.18")
  implicit val indexAlgebraNatDeprecated: Index#Algebra ~> Program = Lambda[Index#Algebra ~> Program] {
    fa => indexFreeMonad.liftF(Coyoneda.lift(fa))
  }

  implicit def indexAlgebraNat[T[_]](implicit inj: CopK.Inject[T, Index#Algebra]): T ~> Program = Lambda[T ~> Program] {
    fa => indexFreeMonad.liftF(Coyoneda.lift(inj(fa)))
  }

  @deprecated("This is not needed any more when using new Api classes", "2.0.18")
  implicit val localAlgebraNatDeprecated: Index#LocalAlgebra ~> Program = Lambda[Index#LocalAlgebra ~> Program] {
    fa => localIndexQueryMonad.liftF(Coyoneda.lift(fa))
  }

  implicit def localAlgebraNat[T[_]](implicit inj: CopK.Inject[T, Index#LocalAlgebra]): T ~> Program = Lambda[T ~> Program] {
    fa => localIndexQueryMonad.liftF(Coyoneda.lift(inj(fa)))
  }

  @inline def pure[A](v: A): Program[A] = programMonad.pure(v)

  @inline def ask: Program[Environment] = environmentReaderMonad.ask
  @inline def local[A](f: Environment => Environment)(fa: Program[A]): Program[A] = environmentReaderMonad.local(f)(fa)
  @inline def scope[A](k: Environment)(fa: Program[A]): Program[A] = environmentReaderMonad.scope(k)(fa)
  @inline def asks[A](f: Environment => A): Program[A] = environmentReaderMonad.asks(f)

  @inline def writer[A](w: Events, v: A): Program[A] = eventWriterMonad.writer(w, v)
  @inline def tell(w: Events): Program[Unit] = eventWriterMonad.tell(w)

  @inline def state[A](a: A): Program[A] = stateMonad.state(a)
  @inline def constantState[A](a: A, s: => State): Program[A] = stateMonad.constantState(a, s)
  @inline def init: Program[State] = stateMonad.init
  @inline def get: Program[State] = stateMonad.get
  @inline def gets[A](f: State => A): Program[A] = stateMonad.gets(f)
  @inline def put(s: State): Program[Unit] = stateMonad.put(s)
  @inline def modify(f: State => State): Program[Unit] = stateMonad.modify(f)

  @inline def getTransient: Program[TransientState] = transientStateMonad.get
  @inline def getsTransient[A](f: TransientState => A): Program[A] = transientStateMonad.gets(f)
  @inline def putTransient(s: TransientState): Program[Unit] = transientStateMonad.put(s)
  @inline def modifyTransient(f: TransientState => TransientState): Program[Unit] = transientStateMonad.modify(f)

  @inline def raiseError[A](e: ResponseError): Program[A] = errorMonad.raiseError(e)
  @inline def handleError[A](fa: Program[A])(f: ResponseError => Program[A]): Program[A] = errorMonad.handleError(fa)(f)

  @inline def log(f: LoggingAdapter => Unit): Program[Unit] = logWriterMonad.tell(Vector(f))

  @inline def emit(events: EventType*): Program[Unit] = for {
    _ <- modify(events.foldLeft(_)(persistentState.process))
    _ <- tell(events.toVector)
  } yield ()
}