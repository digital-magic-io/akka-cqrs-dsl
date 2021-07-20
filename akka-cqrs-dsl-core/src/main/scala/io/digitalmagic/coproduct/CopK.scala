package io.digitalmagic.coproduct

import scalaz.~>

final class CopK[L <: TListK, A] private[coproduct](val index: Int, val value: Any) extends Serializable {
  override def equals(anyOther: Any): Boolean = anyOther match {
    case other: CopK[L, A] => (index == other.index) && (value == other.value)
    case _                  => false
  }

  override def hashCode(): Int = 41 * index + value.##
  override def toString: String = s"CopK($value @ $index)"
}

object CopK {
  final class InjectL[E[_], L <: TListK] private[InjectL](index: Int) {
    def inj[A](fa: E[A]): CopK[L, A] = new CopK[L, A](index, fa)
    def prj[A](ca: CopK[L, A]): Option[E[A]] =
      if (ca.index == index) Some(ca.value.asInstanceOf[E[A]])
      else None
    def apply[A](fa: E[A]): CopK[L, A] = inj(fa)
    def unapply[A](ca: CopK[L, A]): Option[E[A]] = prj(ca)
  }

  object InjectL {
    def apply[E[_], L <: TListK](implicit ev: InjectL[E, L]): InjectL[E, L] = ev
    implicit def makeInjectL[E[_], L <: TListK](implicit ev: TListK.Pos[E, L]): InjectL[E, L] = new InjectL[E, L](ev.index)
  }

  sealed trait Inject[E[_], C[A] <: CopK[_, A]] {
    def inj: E ~> C
    def prj: C ~> Lambda[A => Option[E[A]]]
    final def apply[A](fa: E[A]): C[A] = inj(fa)
    final def unapply[A](ga: C[A]): Option[E[A]] = prj(ga)
  }

  object Inject {
    def apply[E[_], C[A] <: CopK[_, A]](implicit I: Inject[E, C]): Inject[E, C] = I

    implicit def injectKFromInjectL[E[_], L <: TListK](implicit I: InjectL[E, L]): Inject[E, CopK[L, *]] = new Inject[E, CopK[L, *]] {
      val inj: E ~> CopK[L, *] = Lambda[E ~> CopK[L, *]](I.inj(_))
      val prj: CopK[L, *] ~> Lambda[A => Option[E[A]]] = Lambda[CopK[L, *] ~> Lambda[A => Option[E[A]]]](I.prj(_))
    }
  }

  sealed trait Summon[L <: TListK, R[_]] {
    def instances: List[Lambda[A => Any] ~> R]
  }

  object Summon {
    def apply[L <: TListK, R[_]](implicit S: Summon[L, R]): Summon[L, R] = S

    implicit def tnilKSummon[R[_]]: Summon[TNilK, R] = new Summon[TNilK, R] {
      override def instances: List[Lambda[A => Any] ~> R] = List.empty
    }

    implicit def tconsKSummon[H[_], T <: TListK, R[_]](implicit H: H ~> R, T: Summon[T, R]): Summon[TConsK[H, T], R] = new Summon[TConsK[H, T], R] {
      override def instances: List[Lambda[A => Any] ~> R] = H.asInstanceOf[Lambda[A => Any] ~> R] :: T.instances
    }
  }

  object NaturalTransformation {
    final class Builder[R[_]] {
      def summon[L <: TListK](implicit S: Summon[L, R]): CopK[L, *] ~> R = NaturalTransformation.summon[L, R]
    }
    def apply[R[_]]: Builder[R] = new Builder[R]
    def summon[L <: TListK, R[_]](implicit S: Summon[L, R]): CopK[L, *] ~> R = {
      val instances = S.instances.toArray
      Lambda[CopK[L, *] ~> R](cop => instances(cop.index)(cop.value))
    }
  }
}