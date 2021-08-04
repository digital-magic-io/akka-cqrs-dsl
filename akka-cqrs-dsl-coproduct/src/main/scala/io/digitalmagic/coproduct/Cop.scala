package io.digitalmagic.coproduct

final class Cop[L <: TList] private (val index: Int, val value: Any) extends Serializable {
  override def equals(anyOther: Any): Boolean = anyOther match {
    case other: Cop[L] => (index == other.index) && (value == other.value)
    case _              => false
  }

  override def hashCode(): Int = 41 * index + value.##
  override def toString: String = s"CopK($value @ $index)"
}

object Cop {
  final class InjectL[E, L <: TList] private[InjectL](index: Int) {
    def inj(a: E): Cop[L] = new Cop[L](index, a)
    def proj(c: Cop[L]): Option[E] =
      if (c.index == index) Some(c.value.asInstanceOf[E])
      else None
    def apply(a: E): Cop[L] = inj(a)
    def unapply(c: Cop[L]): Option[E] = proj(c)
  }

  object InjectL {
    def apply[E, L <: TList](implicit ev: InjectL[E, L]): InjectL[E, L] = ev
    implicit def makeInjectL[E, L <: TList](implicit ev: TList.Pos[E, L]): InjectL[E, L] = new InjectL[E, L](ev.index)
  }

  sealed trait Inject[E, C <: Cop[_]] {
    def inj: E => C
    def prj: C => Option[E]
    final def apply(a: E): C = inj(a)
    final def unapply(b: C): Option[E] = prj(b)
  }

  object Inject {
    def apply[E, C <: Cop[_]](implicit ev: Inject[E, C]): Inject[E, C] = ev

    implicit def injectFromInjectL[E, L <: TList](implicit ev: InjectL[E, L]): Inject[E, Cop[L]] = new Inject[E, Cop[L]] {
      val inj: E => Cop[L] = ev.inj
      val prj: Cop[L] => Option[E] = ev.proj
    }
  }

  sealed trait Summon[L <: TList, +R] {
    def instances: List[Any => R]
  }

  object Summon {
    def apply[L <: TList, R](implicit S: Summon[L, R]): Summon[L, R] = S

    implicit val tnilSummon: Summon[TNil, Nothing] = new Summon[TNil, Nothing] {
      override def instances: List[Any => Nothing] = List.empty
    }

    implicit def tconsSummon[H, T <: TList, R](implicit H: H => R, T: Summon[T, R]): Summon[TCons[H, T], R] = new Summon[TCons[H, T], R] {
      override def instances: List[Any => R] = H.asInstanceOf[Any => R] :: T.instances
    }
  }

  object Function {
    final class Builder[R] {
      def summon[L <: TList](implicit S: Summon[L, R]): Cop[L] => R = Function.summon[L, R]
    }
    def apply[R]: Builder[R] = new Builder[R]
    def summon[L <: TList, R](implicit S: Summon[L, R]): Cop[L] => R = {
      val instances = S.instances.toArray
      cop => instances(cop.index)(cop.value)
    }
  }
}