package io.digitalmagic.akka.dsl

import io.digitalmagic.akka.dsl.context.ProgramContextOps

import scala.concurrent.duration._

case class TestUniqueIndexActorDef[I <: UniqueIndexApi](indexApi: I, name: String, passivateIn: FiniteDuration = 5 seconds, numberOfShards: Int = 100)(implicit I: UniqueIndexInterface[I]) extends AbstractUniqueIndexActorDef(indexApi, name, passivateIn, numberOfShards) {
  override def construct(indexApi: I, name: String, entityId: String, passivateIn: FiniteDuration)(implicit I: UniqueIndexInterface[I]): AbstractUniqueIndexActor[I] = TestUniqueIndexActor(indexApi, name, entityId, passivateIn)
}

case class TestUniqueIndexActor[I <: UniqueIndexApi](override val indexApi: I, name: String, entityId: String, passivateIn: FiniteDuration = 5 seconds)(implicit I: UniqueIndexInterface[I]) extends AbstractUniqueIndexActor(indexApi, name, entityId, passivateIn) {
  override val contextOps: ProgramContextOps = new ProgramContextOps
}
