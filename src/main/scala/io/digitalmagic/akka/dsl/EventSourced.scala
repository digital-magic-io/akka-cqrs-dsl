package io.digitalmagic.akka.dsl

import java.time.Instant

import iotaz._
import scalaz._
import simulacrum._

import scala.reflect.ClassTag

trait Event extends Product with Serializable {
  var timestamp: Instant = Instant.now()
}

trait PersistentState extends Product with Serializable {
  Self =>
  type EventType <: Event
}

@typeclass trait PersistentStateProcessor[T <: PersistentState] {
  def empty: T
  def process(state: T, event: T#EventType): T
}

trait EventSourced {

  type EventType
  implicit val eventTypeTag: ClassTag[EventType]

  type State <: PersistentState { type EventType = EventSourced.this.EventType }
  implicit val stateTag: ClassTag[State]
  implicit val persistentState: PersistentStateProcessor[State]
}

class ApiHelper[F[A], Alg[A] <: CopK[_, A], Program[_]](implicit val I: CopK.Inject[F, Alg], val T: Alg ~> Program) {
  implicit def lift[A](fa: F[A]): Program[A] = T(I(fa))
}
