package io.digitalmagic.coproduct

sealed trait TListK extends Product with Serializable
sealed trait TNilK extends TListK
sealed trait TConsK[H[_], T <: TListK] extends TListK

object TListK {
  type ::[H[_], T <: TListK] = TConsK[H, T]
  type :::[H[_], T <: TListK] = TConsK[H, T]

  sealed trait Pos[E[_], L <: TListK] {
    val index: Int
  }

  object Pos {
    def apply[E[_], L <: TListK](implicit P: Pos[E, L]): Pos[E, L] = P

    implicit def tlistKHeadPos[E[_], T <: TListK]: Pos[E, TConsK[E, T]] = new Pos[E, TConsK[E, T]] {
      override val index: Int = 0
    }

    implicit def tlistKTailPos[E[_], H[_], T <: TListK](implicit P: Pos[E, T]): Pos[E, TConsK[H, T]] = new Pos[E, TConsK[H, T]] {
      override val index: Int = P.index + 1
    }
  }
}
