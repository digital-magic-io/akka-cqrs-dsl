package io.digitalmagic.akka.dsl

import akka.actor.{NoSerializationVerificationNeeded, ReceiveTimeout}
import akka.cluster.sharding.ShardRegion.Passivate
import akka.event.{Logging, LoggingAdapter}
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotMetadata, SnapshotOffer}
import io.digitalmagic.akka.dsl.API._
import io.digitalmagic.akka.dsl.context.{ProgramContext, SerializedProgramContext}
import scalaz._
import scalaz.syntax.invariantFunctor._
import scalaz.Scalaz._
import scalaz.Tags._

import scala.collection.immutable
import scala.concurrent.{Future, Promise}
import scala.util.{Failure => TryFailure, Success => TrySuccess}

trait DummyActor extends PersistentActor {
  def receiveRecoverSnapshotOffer(metadata: SnapshotMetadata, snapshot: Any): Unit
  def receiveRecoverEvent: Receive = PartialFunction.empty
  def receiveRecoverRecoveryComplete(): Unit = {}

  final override def receiveRecover: Receive = {
    case SnapshotOffer(metadata, snapshot) => receiveRecoverSnapshotOffer(metadata, snapshot)
    case RecoveryCompleted => receiveRecoverRecoveryComplete()
    case event if receiveRecoverEvent.isDefinedAt(event) => receiveRecoverEvent(event)
  }

  override def receiveCommand: Receive = PartialFunction.empty
}

object EventSourcedActorWithInterpreter {
  type IndexResult[T] = ((() => Unit) => Unit, IndexPostActions, Throwable \/ T)
  type IndexFuture[T] = Future[IndexResult[T]]

  case class EventSourcedActorState[+State <: PersistentState](underlying: State, indexesState: ClientIndexesStateMap = ClientIndexesStateMap())
  case object Stop extends NoSerializationVerificationNeeded

  trait IndexPostActionKey {
    type I <: UniqueIndexApi
    val api: I
    val key: api.KeyType
  }
  object IndexPostActionKey {
    def apply[I0 <: UniqueIndexApi](a: I0)(k: a.KeyType): IndexPostActionKey = new IndexPostActionKey {
      override type I = I0
      override val api: a.type = a
      override val key: api.KeyType = k
      override def hashCode(): Int = key.hashCode()
      override def equals(obj: Any): Boolean = obj match {
        case that: IndexPostActionKey => equalsToKey(that)
        case _ => false
      }
      def equalsToKey(that: IndexPostActionKey): Boolean = api == that.api && key == that.key
      override def toString: String = s"$api-$key"
    }
  }
  case class IndexPostAction(commit: () => Unit, commitEvent: Option[UniqueIndexApi#ClientEventType], rollback: () => Unit, rollbackEvent: Option[UniqueIndexApi#ClientEventType])
  type IndexPostActionsMap = Map[IndexPostActionKey, IndexPostAction @@ LastVal]
  case class IndexPostActions(actions: IndexPostActionsMap) {
    def commit: () => Unit = () => actions.values.foreach(LastVal.unwrap(_).commit())
    def commitEvents: Vector[UniqueIndexApi#ClientEventType] = actions.values.flatMap(LastVal.unwrap(_).commitEvent).toVector
    def rollback: () => Unit = () => actions.values.foreach(LastVal.unwrap(_).rollback())
    def rollbackEvents: Vector[UniqueIndexApi#ClientEventType] = actions.values.flatMap(LastVal.unwrap(_).rollbackEvent).toVector
  }
  object IndexPostActions {
    implicit val indexPostActionsMonoid: Monoid[IndexPostActions] = Monoid[IndexPostActionsMap].xmap(map => IndexPostActions(map), _.actions)
    def empty: IndexPostActions = indexPostActionsMonoid.zero
    def apply[I <: UniqueIndexApi](api: I)(key: api.KeyType, commit: () => Unit, commitEvent: Option[api.ClientEventType], rollback: () => Unit, rollbackEvent: Option[api.ClientEventType]): IndexPostActions =
      IndexPostActions(Map(IndexPostActionKey(api)(key) -> LastVal(IndexPostAction(commit, commitEvent, rollback, rollbackEvent))))

    def commitAcquisition[I <: UniqueIndexApi, T[_], E](api: UniqueIndexApi.IndexApiAux[E, I, T])(entityId: E, key: api.KeyType)(implicit A: IndexInterface[I], context: SerializedProgramContext): IndexPostActions =
      IndexPostActions(api)(key,
        () => A.lowLevelApi(api).commitAcquisition(entityId, key),
        Some(api.AcquisitionCompletedClientEvent(key)),
        () => A.lowLevelApi(api).rollbackAcquisition(entityId, key),
        Some(api.AcquisitionAbortedClientEvent(key))
      )

    def rollbackAcquisition[I <: UniqueIndexApi, T[_], E](api: UniqueIndexApi.IndexApiAux[E, I, T])(entityId: E, key: api.KeyType)(implicit A: IndexInterface[I], context: SerializedProgramContext): IndexPostActions =
      IndexPostActions(api)(key,
        () => A.lowLevelApi(api).rollbackAcquisition(entityId, key),
        Some(api.AcquisitionAbortedClientEvent(key)),
        () => A.lowLevelApi(api).rollbackAcquisition(entityId, key),
        Some(api.AcquisitionAbortedClientEvent(key))
      )

    def commitRelease[I <: UniqueIndexApi, T[_], E](api: UniqueIndexApi.IndexApiAux[E, I, T])(entityId: E, key: api.KeyType)(implicit A: IndexInterface[I], context: SerializedProgramContext): IndexPostActions =
      IndexPostActions(api)(key,
        () => A.lowLevelApi(api).commitRelease(entityId, key),
        Some(api.ReleaseCompletedClientEvent(key)),
        () => A.lowLevelApi(api).rollbackRelease(entityId, key),
        Some(api.ReleaseAbortedClientEvent(key))
      )

    def rollbackRelease[I <: UniqueIndexApi, T[_], E](api: UniqueIndexApi.IndexApiAux[E, I, T])(entityId: E, key: api.KeyType)(implicit A: IndexInterface[I], context: SerializedProgramContext): IndexPostActions =
      IndexPostActions(api)(key,
        () => A.lowLevelApi(api).rollbackRelease(entityId, key),
        Some(api.ReleaseAbortedClientEvent(key)),
        () => A.lowLevelApi(api).rollbackRelease(entityId, key),
        Some(api.ReleaseAbortedClientEvent(key))
      )
  }
}

trait EventSourcedActorWithInterpreter extends DummyActor with MonadTellExtras with MonadStateExtras {
  Self: EventSourcedPrograms =>

  import EventSourcedActorWithInterpreter._
  import UniqueIndexApi._
  import context.dispatcher

  val logger: LoggingAdapter = Logging.getLogger(context.system, this)
  type Logger[T] = WriterT[Log, Identity, T]
  type QueryY[T] = Coyoneda[QueryAlgebra, T]
  type IndexY[T] = Coyoneda[Index#Algebra, T]
  type LocalY[T] = Coyoneda[Index#LocalAlgebra, T]
  type QueryT[M[_], T] = FreeT[QueryY, M, T]
  type IndexT[M[_], T] = FreeT[IndexY, M, T]
  type LocalT[M[_], T] = FreeT[LocalY, M, T]
  type Result[T] = ResponseError \/ (LocalY[LocalT[EitherT[ResponseError, IndexT[QueryT[Logger, *], *], *], (TransientState, (Events, T, State))]] \/ (TransientState, (Events, T, State)))

  type Program[A] = RWST[(ProgramContext, Environment), Events, State, StateT[TransientState, LocalT[EitherT[ResponseError, IndexT[QueryT[Logger, *], *], *], *], *], A]

  override lazy val programMonad: Monad[Program] = Monad[Program](rwstMonadTell)

  private type IndexStep[T] = FreeT[Coyoneda[Index#Algebra, *], FreeT[Coyoneda[QueryAlgebra, *], Logger, *], Result[T]]
  private type QueryStep[T] = QueryT[Logger, IndexY[IndexStep[T]] \/ Result[T]]
  private type StepResult[T] = Identity[(Log, QueryY[QueryStep[T]] \/ (IndexY[IndexStep[T]] \/ Result[T]))]

  override lazy val environmentReaderMonad: MonadReader[Program, (ProgramContext, Environment)] = MonadReader[Program, (ProgramContext, Environment)]
  override lazy val eventWriterMonad: MonadTell[Program, Events] = MonadTell[Program, Events]
  override lazy val stateMonad: MonadState[Program, State] = MonadState[Program, State]
  override lazy val transientStateMonad: MonadState[Program, TransientState] = MonadState[Program, TransientState]
  override lazy val localIndexQueryMonad: MonadFree[Program, Coyoneda[Index#LocalAlgebra, *]] = MonadFree[Program, Coyoneda[Index#LocalAlgebra, *]]
  override lazy val errorMonad: MonadError[Program, ResponseError] = MonadError[Program, ResponseError]
  override lazy val freeMonad: MonadFree[Program, Coyoneda[QueryAlgebra, *]] = MonadFree[Program, Coyoneda[QueryAlgebra, *]]
  override lazy val indexFreeMonad: MonadFree[Program, Coyoneda[Index#Algebra, *]] = MonadFree[Program, Coyoneda[Index#Algebra, *]]
  override lazy val logWriterMonad: MonadTell[Program, Log] = MonadTell[Program, Log]

  implicit def unitToConstUnit[A](x: Unit): Const[Unit, A] = Const(x)

  implicit val indexFutureFunctor: Functor[IndexFuture] = Functor[Future] compose Functor[((() => Unit) => Unit, IndexPostActions, *)] compose Functor[Throwable \/ *]

  def entityId: EntityIdType
  def interpreter: QueryAlgebra ~> LazyFuture
  def indexInterpreter: Index#Algebra ~> IndexFuture
  def clientApiInterpreter: Index#ClientAlgebra ~> Const[Unit, *]
  def localApiInterpreter: Index#LocalAlgebra ~> Id

  type ClientEventInterpreter = Index#ClientEventAlgebra => ClientIndexesStateMap => ClientIndexesStateMap
  implicit def genClientEventInterpreter[E, I <: UniqueIndexApi, T](implicit api: UniqueIndexApi.ClientEventAux[E, I, T]): T => ClientIndexesStateMap => ClientIndexesStateMap = e => s => s.process(api)(e)
  def clientEventInterpreter: ClientEventInterpreter

  protected var state: EventSourcedActorState[State] = EventSourcedActorState(persistentState.empty)
  private var transientState: TransientState = initialTransientState
  private var needsPassivation: Boolean = false
  private var stashingBehaviourActive: Boolean = false
  private def checkAndPassivate(): Unit = {
    if (needsPassivation && !stashingBehaviourActive) {
      logger.debug(s"$persistenceId: stopped...")
      context.stop(self)
    } else if (needsPassivation) {
      logger.debug(s"$persistenceId: not stopping, program is being executed")
    }
  }
  private def passivate(): Unit = {
    needsPassivation = true
    checkAndPassivate()
  }
  private def activateStashingBehaviour(): Unit = {
    context.become(stashingBehaviour, false)
    stashingBehaviourActive = true
  }
  private def deactivateStashingBehaviour(): Unit = {
    context.unbecome()
    unstashAll()
    stashingBehaviourActive = false
    checkAndPassivate()
  }

  protected def persistEvents[A](events: immutable.Seq[A])(handler: A => Unit, completion: Boolean => Unit): Unit = {
    if (events.isEmpty) {
      completion(false)
    } else {
      var processed = 0
      persistAll(events) { event =>
        processed += 1
        handler(event)
        if (processed == events.size) {
          completion(true)
        }
      }
    }
  }

  private def processIndexEvent(event: UniqueIndexApi#ClientEvent): Unit = {
    clientRuntime.injectEvent(event) match {
      case Some(e) => state = state.copy(indexesState = clientEventInterpreter(e)(state.indexesState))
      case None => logger.warning(s"unknown client index event: $event")
    }
  }

  override def receiveRecoverSnapshotOffer(metadata: SnapshotMetadata, snapshot: Any): Unit = snapshot match{
    case s: EventSourcedActorState[_] =>
      processSnapshot(s.underlying) match {
        case Some(x) =>
          state = EventSourcedActorState(x, s.indexesState.filter(clientRuntime.hasIndex))
        case None =>
          logger.error(s"$persistenceId: failed to process SnapshotOffer [$metadata], remove and continue")
          deleteSnapshot(metadata.sequenceNr)
          throw new RuntimeException("failed to process snapshot")
      }
    case _ =>
      logger.error(s"persistenceId: failed to process SnapshotOffer [$metadata]: unknown snapshot class: [${snapshot.getClass}], remove and continue")
      deleteSnapshot(metadata.sequenceNr)
      throw new RuntimeException("failed to process snapshot")
  }

  abstract override def receiveRecoverEvent: Receive = super.receiveRecoverEvent orElse {
    case event: UniqueIndexApi#ClientEvent =>
      processIndexEvent(event)

    case event: EventType =>
      val events = preprocessEventOnRestore(event)
      state = state.copy(underlying = events.foldLeft(state.underlying)(persistentState.process))
  }

  abstract override def receiveRecoverRecoveryComplete(): Unit = {
    super.receiveRecoverRecoveryComplete()
    val actions = state.indexesState.map.toList.foldMap { case (api, _) =>
      val apiState = state.indexesState.get(api).get
      apiState.map.toList.foldMap {
        case (key, apiState.Api.AcquisitionPendingClientState()) => IndexPostActions(api)(key, () => (), None, () => (), Some(api.AcquisitionAbortedClientEvent(key)))
        case (key, apiState.Api.ReleasePendingClientState()) => IndexPostActions(api)(key, () => (), None, () => (), Some(api.ReleaseAbortedClientEvent(key)))
        case (key, apiState.Api.AcquiredClientState()) => IndexPostActions.empty
      }
    }
    rollback(false, actions)
  }

  override protected def onPersistRejected(cause: Throwable, event: Any, seqNr: Long): Unit = {
    super.onPersistRejected(cause, event, seqNr)
    context.stop(self)
  }

  private def rollback(normalMode: Boolean, postActions: IndexPostActions): Unit = {
    val events = postActions.rollbackEvents

    persistEvents(events)(
      handler = { event =>
        processIndexEvent(event)
      },
      completion = { _ =>
        postActions.rollback()
        if (normalMode) {
          deactivateStashingBehaviour()
        }
      }
    )
  }

  private def commit(events: Events, postActions: IndexPostActions)(completion: () => Unit): Unit = {
    val indexEvents = postActions.commitEvents

    persistEvents(preprocessEventsOnPersist(events) ++ indexEvents)(
      handler = {
        case event: UniqueIndexApi#ClientEvent =>
          processIndexEvent(event)
        case _ =>
      },
      completion = { _ =>
        postActions.commit()
        deactivateStashingBehaviour()
        completion()
      }
    )
  }

  implicit def interpretIndex[I <: UniqueIndexApi, T[_]](implicit api: UniqueIndexApi.IndexApiAux[EntityIdType, I, T],
                                                         A: IndexInterface[I]): T ~> IndexFuture = new (T ~> IndexFuture) {

    override def apply[A](fa: T[A]): IndexFuture[A] = api.castIndexApi(fa) match {
      case c@api.Acquire(keys) =>
        val (events, indexPostActions) = keys.toList.foldMap {
          key => state.indexesState.get(api).flatMap(_.get(key)) match {
            case None | Some(api.AcquisitionPendingClientState()) =>
              // not yet acquired
              // or acquisition has been started, in this case re-acquire because previous attempt could fail with duplicate key, but that has not been reflected in our state
              (List(api.AcquisitionStartedClientEvent(key)), IndexPostActions.commitAcquisition(api)(entityId, key)(A, c.context))
            case Some(api.ReleasePendingClientState()) =>
              // release has been started, so roll it back
              (List(), IndexPostActions.rollbackRelease(api)(entityId, key)(A, c.context))
            case Some(api.AcquiredClientState()) =>
              // already acquired
              (List(), IndexPostActions.empty)
          }
        }
        val p = Promise[IndexResult[Unit]]()
        persistEvents(events)(
          handler = { event =>
            processIndexEvent(event)
          },
          completion = { _ =>
            events.traverse(e => A.lowLevelApi(api).startAcquisition(entityId, e.key)(c.context)(dispatcher)) onComplete {
              case TrySuccess(_) => p.success((f => f(), indexPostActions, \/-(())))
              case TryFailure(cause) => p.success((f => f(), indexPostActions, -\/(cause)))
            }
          }
        )
        p.future
      case c@api.Release(keys) =>
        val (events, indexPostActions) = keys.toList.foldMap {
          key => state.indexesState.get(api).flatMap(_.get(key)) match {
            case None => // not yet acquired
              (List(), IndexPostActions.empty)
            case Some(api.AcquisitionPendingClientState()) => // acquisition has been started, so roll it back
              (List(), IndexPostActions.rollbackAcquisition(api)(entityId, key)(A, c.context))
            case Some(api.ReleasePendingClientState()) => // release has been started
              (List(), IndexPostActions.commitRelease(api)(entityId, key)(A, c.context))
            case Some(api.AcquiredClientState()) => // already acquired
              (List(api.ReleaseStartedClientEvent(key)), IndexPostActions.commitRelease(api)(entityId, key)(A, c.context))
          }
        }
        events.traverse(e => A.lowLevelApi(api).startRelease(entityId, e.key)(c.context)(dispatcher)) map { _ =>
          ((f: () => Unit) => {
            persistEvents(events)(
              handler = { event => processIndexEvent(event) },
              completion = { _ => f() }
            )
          }, indexPostActions, \/-(()))
        }
    }
  }

  implicit def interpretLocalApi[I <: UniqueIndexApi, T[_]](implicit api: UniqueIndexApi.LocalApiAux[EntityIdType, I, T]): T ~> Id = Lambda[T ~> Id] {
    case api.GetMyEntries => state.indexesState.get(api).fold(Set.empty[api.KeyType])(_.acquiredKeys)
  }

  private trait NextStep {
    type T
    val request: Request[T]
    val context: ProgramContext
    def resume: StepResult[T]
    def continuation: (() => Unit) => Unit
    def postActions: IndexPostActions
    def nextIndexStep(continuation: (() => Unit) => Unit, post: IndexPostActions, rest: IndexStep[T]): NextStep =
      NextStep(request, context, continuation, postActions |+| post, rest)

    def nextQueryStep(next: QueryStep[T]): NextStep = new NextStep with NoSerializationVerificationNeeded {
      override type T = NextStep.this.T
      override val request: Request[T] = NextStep.this.request
      override val context: ProgramContext = NextStep.this.context
      override def resume: StepResult[T] = next.resume.run
      override def continuation: (() => Unit) => Unit = f => f()
      override def postActions: IndexPostActions = NextStep.this.postActions
    }
  }

  private object NextStep {
    def apply[U](r: Request[U], ctx: ProgramContext, c: (() => Unit) => Unit, post: IndexPostActions, s: IndexStep[U]): NextStep = new NextStep with NoSerializationVerificationNeeded {
      override type T = U
      override val request: Request[T] = r
      override val context: ProgramContext = ctx
      override def resume: StepResult[T] = s.resume.resume.run
      override def continuation: (() => Unit) => Unit = c
      override def postActions: IndexPostActions = post
    }
  }

  private case class FailStep[U](rollbackActions: IndexPostActions, context: ProgramContext, request: Request[U], error: Throwable) extends NoSerializationVerificationNeeded

  def interpretStep(step: NextStep): Unit = {
    step.continuation { () =>
      val Need((log, programRes)) = step.resume
      log.foreach(_(logger))

      programRes match {
        case \/-(\/-(-\/(error))) =>
          rollback(true, step.postActions)
          closeContext(step.context, Some(error.getMessage))
          sender() ! step.request.failure(error)

        case \/-(\/-(\/-(\/-((newTransientState, (events, result, newState)))))) =>
          commit(events, step.postActions) { () =>
            state = state.copy(underlying = newState)
            transientState = newTransientState
            closeContext(step.context, None)
            sender() ! step.request.success(result)
          }

        case \/-(\/-(\/-(-\/(local)))) =>
          interpretStep(step.nextIndexStep(f => f(), IndexPostActions.empty, local.trans(localApiInterpreter).run.resume.run))

        case \/-(-\/(idx)) =>
          val replyTo = sender()
          idx.trans(indexInterpreter).run onComplete {
            case scala.util.Success((cont, postActions, \/-(res))) => self.tell(step.nextIndexStep(cont, postActions, res), replyTo)
            case scala.util.Success((_, postActions, -\/(err))) => self.tell(FailStep(step.postActions |+| postActions, step.context, step.request, err), replyTo)
            case scala.util.Failure(err) => self.tell(FailStep(step.postActions, step.context, step.request, err), replyTo)
          }

        case -\/(ex) =>
          val replyTo = sender()
          val queryFuture = ex.trans(interpreter).run
          queryFuture(dispatcher) onComplete {
            case scala.util.Success(rest) => self.tell(step.nextQueryStep(rest), replyTo)
            case scala.util.Failure(err) => self.tell(FailStep(step.postActions, step.context, step.request, err), replyTo)
          }
      }
    }
  }

  private def interpret[T](r: Request[T], context: ProgramContext, environment: Environment, program: Program[T]): Unit =
    interpretStep(NextStep(r, context, f => f(), IndexPostActions.empty, program.run((context, environment), state.underlying).run(transientState).resume.run))

  private def processNextStep: Receive = {
    case nextStep: NextStep => interpretStep(nextStep)
    case FailStep(rollbackActions, context, request, error) =>
      rollback(true, rollbackActions)
      closeContext(context, Some(error.getMessage))
      error match {
        case e: ResponseError => sender() ! request.failure(e)
        case e                => sender() ! request.failure(InternalError(e))
      }
  }

  implicit def clientQueryHandler[I <: UniqueIndexApi, T[_]](implicit api: UniqueIndexApi.ClientQueryAux[EntityIdType, I, T]): T ~> Const[Unit, *] = Lambda[T ~> Const[Unit, *]] {
    case q@api.IsIndexNeeded(entityId, key) if entityId != EventSourcedActorWithInterpreter.this.entityId =>
      logger.warning(s"entity id mismatch: got request [$q], while my entity id is [${EventSourcedActorWithInterpreter.this.entityId}]")
      sender() ! q.failure(api.EntityIdMismatch(EventSourcedActorWithInterpreter.this.entityId, entityId, key))
    case q@api.IsIndexNeeded(_, key) =>
      val response = state.indexesState.get(api).flatMap(_.get(key)) match {
        case Some(api.AcquisitionPendingClientState()) => IsIndexNeededResponse.Unknown
        case Some(api.ReleasePendingClientState())     => IsIndexNeededResponse.Unknown
        case Some(api.AcquiredClientState())           => IsIndexNeededResponse.Yes
        case None                                      => IsIndexNeededResponse.No
      }
      sender() ! q.success(response)
  }

  private def handlePassivation: Receive = {
    case ReceiveTimeout =>
      logger.debug(s"$persistenceId: passivate ...")
      context.parent ! Passivate(stopMessage = Stop)

    case Stop =>
      passivate()
  }

  private val classNamePattern = """.*?(\w+)\$?\z""".r
  private def startNewContext(r: Request[_]): ProgramContext = {
    val classNamePattern(name) = r.getClass.getName
    r match {
      case r: HasContext => contextOps.startSubContext(r.context, name)
      case _ => contextOps.emptyContext(name)
    }
  }

  private def closeContext(context: ProgramContext, error: Option[String]): Unit = {
    contextOps.closeContext(context, error)
  }

  private def processIsIndexNeeded: Receive = {
    case q: UniqueIndexApi#ClientQuery[_] =>
      val context = startNewContext(q)
      clientRuntime.injectQuery(q) match {
        case Some(query) =>
          clientApiInterpreter(query)
        case None =>
          logger.warning(s"unknown client api query: $q")
      }
      closeContext(context, None)
  }

  private def interpretRequest: Receive = {
    case request: Request[_] if getProgram(request).isDefined => getProgram(request) match {
      case Some(program) =>
        activateStashingBehaviour()
        interpret(request, startNewContext(request), getEnvironment(request), program)
      case None =>
        sys.error("should not happen")
    }
  }

  private def stashingBehaviour: Receive = super.receiveCommand orElse handlePassivation orElse processNextStep orElse processIsIndexNeeded orElse {
    case _ => stash()
  }

  abstract override def receiveCommand: Receive = super.receiveCommand orElse handlePassivation orElse processNextStep orElse processIsIndexNeeded orElse interpretRequest
}
