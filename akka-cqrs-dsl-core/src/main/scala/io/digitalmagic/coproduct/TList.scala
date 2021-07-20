package io.digitalmagic.coproduct

sealed trait TList extends Product with Serializable
sealed trait TNil extends TList
sealed trait TCons[H, T <: TList] extends TList

object TList {
  type ::[H, T <: TList] = TCons[H, T]
  type :::[H, T <: TList] = TCons[H, T]

  sealed trait Pos[E, L <: TList] extends Any {
    def index: Int
  }
  private case class PosC[E, L <: TList] private(index: Int) extends AnyVal with Pos[E, L]

  object Pos {
    def apply[E, L <: TList](implicit P: Pos[E, L]): Pos[E, L] = P
    implicit def tlistHeadPos[E, T <: TList]: Pos[E, TCons[E, T]] = PosC[E, TCons[E, T]](0)
    implicit def tlistTailPos[E, H, T <: TList](implicit P: Pos[E, T]): Pos[E, TCons[H, T]] = PosC[E, TCons[H, T]](P.index + 1)
  }
}
