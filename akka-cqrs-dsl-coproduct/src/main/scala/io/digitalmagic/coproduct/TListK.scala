package io.digitalmagic.coproduct

sealed trait TListK

object TListK {
  type ::[H[_], T <: TListK] = TConsK[H, T]
  type :::[H[_], T <: TListK] = TConsK[H, T]

  trait Pos[E[_], L <: TListK] {
    def index: Int
  }

  object Pos {
    def apply[F[_], L <: TListK](implicit ev: Pos[F, L]): Pos[F, L] = ev
    implicit def materializePos[F[_], L <: TListK]: Pos[F, L] = macro internal.TypeListMacros.materializeTListKPos[F, L]
  }
}
