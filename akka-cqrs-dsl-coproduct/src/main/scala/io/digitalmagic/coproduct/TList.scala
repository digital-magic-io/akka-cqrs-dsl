package io.digitalmagic.coproduct

sealed trait TList

object TList {
  type ::[H, T <: TList] = TCons[H, T]
  type :::[H, T <: TList] = TCons[H, T]

  trait Pos[E, L <: TList] extends Any {
    def index: Int
  }

  object Pos {
    def apply[F, L <: TList](implicit ev: Pos[F, L]): Pos[F, L] = ev
    implicit def materializePos[F, L <: TList]: Pos[F, L] = macro internal.TypeListMacros.materializeTListPos[F, L]
  }
}
