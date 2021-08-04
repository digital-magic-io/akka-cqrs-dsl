package io.digitalmagic.akka.dsl

import scala.reflect.ClassTag

trait Event extends Product with Serializable {
  type TimestampType
  var timestamp: TimestampType
}

trait PersistentState extends Product with Serializable {
  Self =>
  type EventType <: Event
}

trait PersistentStateProcessor[T <: PersistentState] {
  def empty: T
  def process(state: T, event: T#EventType): T
}

trait EventSourced {

  type EventType
  implicit val eventTypeTag: ClassTag[EventType]

  type State <: PersistentState { type EventType = EventSourced.this.EventType }
  implicit val stateTag: ClassTag[State]
  val persistentState: PersistentStateProcessor[State]
}
