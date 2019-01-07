package io.digitalmagic.akka.dsl

import akka.actor.{NoSerializationVerificationNeeded, PoisonPill, ReceiveTimeout}
import akka.cluster.sharding.ShardRegion.Passivate
import akka.persistence.{PersistentActor, RecoveryCompleted, SnapshotMetadata, SnapshotOffer}
import io.digitalmagic.akka.dsl.API._
import scalaz._
import scalaz.Tags._
import Scalaz._
import akka.event.{Logging, LoggingAdapter}
import iotaz.{TList, evidence}

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
  case class EventSourcedActorState[+State <: PersistentState](underlying: State, indexesState: ClientIndexesStateMap = ClientIndexesStateMap())
  case object Stop

  trait IndexPostActionKey {
    type I <: UniqueIndexApi
    val api: I
    val key: api.KeyType
  }
  object IndexPostActionKey {
    def apply[I0 <: UniqueIndexApi](a: I0)(k: a.KeyType): IndexPostActionKey = new IndexPostActionKey {
      override type I = I0
      override val api: I = a
      override val key: api.KeyType = k.asInstanceOf[api.KeyType]
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
    implicit val indexPostActionsMonoid: Monoid[IndexPostActions] = Monoid.fromIso(Isomorphism.IsoSet[IndexPostActions, IndexPostActionsMap](_.actions, map => IndexPostActions(map)))
    def apply[I <: UniqueIndexApi](api: I)(key: api.KeyType, commit: () => Unit, commitEvent: Option[api.ClientEventType], rollback: () => Unit, rollbackEvent: Option[api.ClientEventType]): IndexPostActions =
      IndexPostActions(Map(IndexPostActionKey(api)(key) -> LastVal(IndexPostAction(commit, commitEvent, rollback, rollbackEvent))))

    def commitAcquisition[I <: UniqueIndexApi, T[_], E](api: UniqueIndexApi.IndexApiAux[E, I, T])(entityId: E, key: api.KeyType)(implicit A: UniqueIndexInterface[I]): IndexPostActions =
      IndexPostActions(api)(key,
        () => A.lowLevelApi(api).commitAcquisition(entityId, key),
        Some(api.AcquisitionCompletedClientEvent(key)),
        () => A.lowLevelApi(api).rollbackAcquisition(entityId, key),
        Some(api.AcquisitionAbortedClientEvent(key))
      )

    def rollbackAcquisition[I <: UniqueIndexApi, T[_], E](api: UniqueIndexApi.IndexApiAux[E, I, T])(entityId: E, key: api.KeyType)(implicit A: UniqueIndexInterface[I]): IndexPostActions =
      IndexPostActions(api)(key,
        () => A.lowLevelApi(api).rollbackAcquisition(entityId, key),
        Some(api.AcquisitionAbortedClientEvent(key)),
        () => A.lowLevelApi(api).commitAcquisition(entityId, key),
        Some(api.AcquisitionCompletedClientEvent(key))
      )

    def commitRelease[I <: UniqueIndexApi, T[_], E](api: UniqueIndexApi.IndexApiAux[E, I, T])(entityId: E, key: api.KeyType)(implicit A: UniqueIndexInterface[I]): IndexPostActions =
      IndexPostActions(api)(key,
        () => A.lowLevelApi(api).commitRelease(entityId, key),
        Some(api.ReleaseCompletedClientEvent(key)),
        () => A.lowLevelApi(api).rollbackRelease(entityId, key),
        Some(api.ReleaseAbortedClientEvent(key))
      )

    def rollbackRelease[I <: UniqueIndexApi, T[_], E](api: UniqueIndexApi.IndexApiAux[E, I, T])(entityId: E, key: api.KeyType)(implicit A: UniqueIndexInterface[I]): IndexPostActions =
      IndexPostActions(api)(key,
        () => A.lowLevelApi(api).rollbackRelease(entityId, key),
        Some(api.ReleaseAbortedClientEvent(key)),
        () => A.lowLevelApi(api).commitRelease(entityId, key),
        Some(api.ReleaseCompletedClientEvent(key))
      )
  }
}

trait EventSourcedActorWithInterpreter extends DummyActor with MonadTellExtras {
  Self: EventSourcedPrograms =>

  import EventSourcedActorWithInterpreter._
  import UniqueIndexApi._
  import context.dispatcher

  val logger: LoggingAdapter = Logging.getLogger(context.system, this)

  type Logger[T] = WriterT[Identity, Log, T]
  type Result[T] = ResponseError \/ (Events, T, State)

  type Program[A] = RWST[EitherT[FreeT[Coyoneda[Index#Algebra, ?], FreeT[Coyoneda[QueryAlgebra, ?], Logger, ?], ?], ResponseError, ?], Environment, Events, State, A]
  override lazy val programMonad: Monad[Program] = Monad[Program]

  private type QueryStep[T] = FreeT[Coyoneda[QueryAlgebra, ?], Logger, Result[T] \/ Coyoneda[Index#Algebra, IndexStep[T]]]
  private type IndexStep[T] = FreeT[Coyoneda[Index#Algebra, ?], FreeT[Coyoneda[QueryAlgebra, ?], Logger, ?], Result[T]]
  private type StepResult[T] = Identity[(Log, Result[T] \/ Coyoneda[Index#Algebra, IndexStep[T]] \/ Coyoneda[QueryAlgebra, QueryStep[T]])]

  override lazy val environmentReaderMonad: MonadReader[Program, Environment] = MonadReader[Program, Environment]
  override lazy val eventWriterMonad: MonadTell[Program, Events] = MonadTell[Program, Events]
  override lazy val stateMonad: MonadState[Program, State] = MonadState[Program, State]
  override lazy val errorMonad: MonadError[Program, ResponseError] = MonadError[Program, ResponseError]
  override lazy val freeMonad: MonadFree[Program, Coyoneda[QueryAlgebra, ?]] = MonadFree[Program, Coyoneda[QueryAlgebra, ?]]
  override lazy val indexFreeMonad: MonadFree[Program, Coyoneda[Index#Algebra, ?]] = MonadFree[Program, Coyoneda[Index#Algebra, ?]]
  override lazy val logWriterMonad: MonadTell[Program, Log] = MonadTell[Program, Log]

  implicit def unitToConstUnit[A](x: Unit): Const[Unit, A] = Const(x)

  type IndexResult[T] = ((() => Unit) => Unit, IndexPostActions, T)
  type IndexFuture[T] = Future[IndexResult[T]]
  implicit val indexFutureFunctor: Functor[IndexFuture] = Functor[Future] compose Functor[IndexResult]

  def entityId: EntityIdType
  def interpreter: QueryAlgebra ~> RequestFuture
  def indexInterpreter: Index#Algebra ~> IndexFuture
  def clientApiInterpreter: Index#ClientAlgebra ~> Const[Unit, ?]

  type ClientEventInterpreter = Index#ClientEventAlgebra => ClientIndexesStateMap => ClientIndexesStateMap
  implicit def genClientEventInterpreter(implicit interpreters: evidence.All[TList.Op.Map[ClientEventInterpreterS[EntityIdType, ?], Index#ClientEventList]]): ClientEventInterpreter =
    e => s => interpreters.underlying.values(e.index).asInstanceOf[ClientEventInterpreterS[EntityIdType, Any]](e.value, s)
  def clientEventInterpreter: ClientEventInterpreter

  private var state: EventSourcedActorState[State] = EventSourcedActorState(persistentState.empty)
  private var needsPassivation: Boolean = false
  private var stashingBehaviourActive: Boolean = false
  private def checkAndPassivate(): Unit = {
    if (needsPassivation && !stashingBehaviourActive) {
      logger.debug(s"$persistenceId: stopped...")
      context.parent ! PoisonPill
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
      persistAll(events) { event =>
        handler(event)
        if (event == events.last) {
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
          state = EventSourcedActorState(x, s.indexesState)
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
      state = state.copy(underlying = persistentState.process(state.underlying, event))
  }

  abstract override def receiveRecoverRecoveryComplete(): Unit = {
    super.receiveRecoverRecoveryComplete()
    rollback(false, IndexPostActions.indexPostActionsMonoid.zero)
  }

  override protected def onPersistRejected(cause: Throwable, event: Any, seqNr: Long): Unit = {
    super.onPersistRejected(cause, event, seqNr)
    context.stop(self)
  }

  def rollback(normalMode: Boolean, postActions: IndexPostActions): Unit = {
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

  def commit(events: Events, postActions: IndexPostActions)(completion: () => Unit): Unit = {
    val indexEvents = postActions.commitEvents

    persistEvents(events ++ indexEvents)(
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
                                                         A: UniqueIndexInterface[I]): T ~> IndexFuture = new (T ~> IndexFuture) {

    override def apply[A](fa: T[A]): IndexFuture[A] = api.castIndexApi(fa) match {
      case api.Acquire(key) =>
        state.indexesState.get(api).flatMap(_.get(key)) match {
          case None => // not yet acquired
            val p = Promise[IndexResult[Unit]]
            persist(api.AcquisitionStartedClientEvent(key)) { event =>
              processIndexEvent(event)
              A.lowLevelApi(api).startAcquisition(entityId, key)(dispatcher) onComplete {
                case TrySuccess(result) => p.success((
                  f => f(),
                  IndexPostActions.commitAcquisition(api)(entityId, key),
                  result
                ))
                case TryFailure(cause) => p.failure(cause)
              }
            }
            p.future
          case Some(api.AcquisitionPendingClientState()) => // acquisition has been started
            Future.successful((f => f(), IndexPostActions.commitAcquisition(api)(entityId, key), ()))
          case Some(api.ReleasePendingClientState()) => // release has been started, so roll it back
            Future.successful((f => f(), IndexPostActions.rollbackRelease(api)(entityId, key), ()))
          case Some(api.AcquiredClientState()) => // already acquired
            Future.successful((f => f(), IndexPostActions.indexPostActionsMonoid.zero, ()))
        }
      case api.Release(key) =>
        state.indexesState.get(api).flatMap(_.get(key)) match {
          case None => // not yet acquired
            Future.successful((f => f(), IndexPostActions.indexPostActionsMonoid.zero, ()))
          case Some(api.AcquisitionPendingClientState()) => // acquisition has been started, so roll it back
            Future.successful((f => f(), IndexPostActions.rollbackAcquisition(api)(entityId, key), ()))
          case Some(api.ReleasePendingClientState()) => // release has been started
            Future.successful((f => f(), IndexPostActions.commitRelease(api)(entityId, key), ()))
          case Some(api.AcquiredClientState()) => // already acquired
            A.lowLevelApi(api).startRelease(entityId, key)(dispatcher) map { result =>
              (
                f => {
                  persist(api.ReleaseStartedClientEvent(key)) { event =>
                    processIndexEvent(event)
                    f()
                  }
                },
                IndexPostActions.commitRelease(api)(entityId, key),
                result
              )
            }
        }
    }
  }

  private trait NextStep {
    type T
    val request: Request[T]
    val environment: Environment
    def resume: StepResult[T]
    def continuation: (() => Unit) => Unit
    def postActions: IndexPostActions
    def nextIndexStep(continuation: (() => Unit) => Unit, post: IndexPostActions, rest: IndexStep[T]): NextStep =
      NextStep(request, environment, continuation, postActions |+| post, rest)

    def nextQueryStep(next: QueryStep[T]): NextStep = new NextStep with NoSerializationVerificationNeeded {
      override type T = NextStep.this.T
      override val request: Request[T] = NextStep.this.request
      override val environment: Environment = NextStep.this.environment
      override def resume: StepResult[T] = next.resume.run
      override def continuation: (() => Unit) => Unit = f => f()
      override def postActions: IndexPostActions = NextStep.this.postActions
    }
  }

  private object NextStep {
    def apply[U](r: Request[U], env: Environment, c: (() => Unit) => Unit, post: IndexPostActions, s: IndexStep[U]): NextStep = new NextStep with NoSerializationVerificationNeeded {
      override type T = U
      override val request: Request[T] = r
      override val environment: Environment = env
      override def resume: StepResult[T] = s.resume.resume.run
      override def continuation: (() => Unit) => Unit = c
      override def postActions: IndexPostActions = post
    }
  }

  def interpretStep(step: NextStep): Unit = {
    step.continuation { () =>
      val Need((log, programRes)) = step.resume
      log.foreach(_(logger))

      programRes match {
        case -\/(-\/(-\/(error))) =>
          rollback(true, step.postActions)
          sender() ! step.request.failure(error)

        case -\/(-\/(\/-((events, result, newState)))) =>
          commit(events, step.postActions) { () =>
            state = state.copy(underlying = newState)
            sender() ! step.request.success(result)
          }

        case -\/(\/-(idx)) =>
          val replyTo = sender()
          idx.trans(indexInterpreter).run onComplete {
            case scala.util.Success(rest) => self.tell(step.nextIndexStep(rest._1, rest._2, rest._3), replyTo)
            case scala.util.Failure(err) =>
              rollback(true, step.postActions)
              err match {
                case e: ResponseError => replyTo ! step.request.failure(e)
                case e                => replyTo ! step.request.failure(InternalError(e))
              }
          }

        case \/-(ex) =>
          val replyTo = sender()
          val queryFuture = ex.trans(interpreter).run
          queryFuture(dispatcher) onComplete {
            case scala.util.Success(rest) => self.tell(step.nextQueryStep(rest), replyTo)
            case scala.util.Failure(err) =>
              rollback(true, step.postActions)
              err match {
                case e: ResponseError => replyTo ! step.request.failure(e)
                case e                => replyTo ! step.request.failure(InternalError(e))
              }
          }
      }
    }
  }

  private def interpret[T](r: Request[T], environment: Environment, program: Program[T]): Unit = {
    interpretStep(NextStep(r, environment, f => f(), IndexPostActions.indexPostActionsMonoid.zero, program.run(environment, state.underlying).run))
  }

  private def processNextStep: Receive = {
    case nextStep: NextStep => interpretStep(nextStep)
  }

  implicit def clientQueryHandler[I <: UniqueIndexApi, T[_]](implicit api: UniqueIndexApi.ClientQueryAux[EntityIdType, I, T]): T ~> Const[Unit, ?] = Lambda[T ~> Const[Unit, ?]] {
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

  private def processIsIndexNeeded: Receive = {
    case q: UniqueIndexApi#ClientQuery[_] =>
      clientRuntime.injectQuery(q) match {
        case Some(query) =>
          clientApiInterpreter(query)
        case None =>
          logger.warning(s"unknown client api query: $q")
      }
  }

  private def interpretRequest: Receive = {
    case request: Request[_] if getProgram(request).isDefined => getProgram(request) match {
      case Some(program) =>
        activateStashingBehaviour()
        interpret(request, getEnvironment(request), program)
      case None =>
        sys.error("should not happen")
    }
  }

  private def stashingBehaviour: Receive = super.receiveCommand orElse handlePassivation orElse processNextStep orElse processIsIndexNeeded orElse {
    case _ => stash()
  }

  abstract override def receiveCommand: Receive = super.receiveCommand orElse handlePassivation orElse processNextStep orElse processIsIndexNeeded orElse interpretRequest
}
