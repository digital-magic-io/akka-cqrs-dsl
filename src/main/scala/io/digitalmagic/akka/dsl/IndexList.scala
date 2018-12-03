package io.digitalmagic.akka.dsl

import iotaz._
import TList.:::
import TListK.{::: => ::::}
import scalaz._

trait ClientEventInterpreterS[E, T] {
  def apply(e: T, s: ClientIndexesStateMap):  ClientIndexesStateMap
}

object ClientEventInterpreterS {
  implicit def clientEventInterpreter[E, I <: UniqueIndexApi, T](implicit api: UniqueIndexApi.ClientEventAux[E, I, T]): ClientEventInterpreterS[E, T] = (e, s) => s.process(api)(e)
}

sealed trait IndexList {
  type This >: this.type <: IndexList
  type List <: TList
  type AlgebraList <: TListK
  type Algebra[A] <: CopK[AlgebraList, A]
  type ClientAlgebraList <: TListK
  type ClientAlgebra[A] <: CopK[ClientAlgebraList, A]
  type ClientEventList <: TList
  type ClientEventAlgebra <: Cop[ClientEventList]

  type + [I <: UniqueIndexApi] = WithIndex[I ::: List, I#IndexApiType :::: AlgebraList, I#ClientQueryType :::: ClientAlgebraList, I#ClientEventType ::: ClientEventList]
}

final class EmptyIndexList extends IndexList {
  type List = TNil
  type AlgebraList = TNilK
  type Algebra[A] = CopK[AlgebraList, A]
  type ClientAlgebraList = TNilK
  type ClientAlgebra[A] = CopK[ClientAlgebraList, A]
  type ClientEventList = TNil
  type ClientEventAlgebra = Cop[ClientEventList]
}

final class WithIndex[L <: TList, AL <: TListK, CAL <: TListK, CEL <: TList] extends IndexList {
  type List = L
  type AlgebraList = AL
  type Algebra[A] = CopK[AlgebraList, A]
  type ClientAlgebraList = CAL
  type ClientAlgebra[A] = CopK[ClientAlgebraList, A]
  type ClientEventList = CEL
  type ClientEventAlgebra = Cop[ClientEventList]
}

trait ClientQueryRuntimeInject[L <: TList, Alg[T] <: CopK[_, T]] {
  def runtimeInject: UniqueIndexApi#ClientQuery ~> Lambda[a => Option[Alg[a]]]
}

object ClientQueryRuntimeInject {
  implicit def base[Alg[T] <: CopK[_, T]]: ClientQueryRuntimeInject[TNil, Alg] = new ClientQueryRuntimeInject[TNil, Alg] {
    override def runtimeInject: UniqueIndexApi#ClientQuery ~> Lambda[a => Option[Alg[a]]] = Lambda[UniqueIndexApi#ClientQuery ~> Lambda[a => Option[Alg[a]]]] {
      _ => None
    }
  }

  implicit def induct[A <: UniqueIndexApi, L <: TList, Alg[T] <: CopK[_, T]](implicit api: A, L: ClientQueryRuntimeInject[L, Alg], I: CopK.Inject[A#ClientQueryType, Alg]): ClientQueryRuntimeInject[A ::: L, Alg] =
    new ClientQueryRuntimeInject[A ::: L, Alg] {
      override def runtimeInject: UniqueIndexApi#ClientQuery ~> Lambda[a => Option[Alg[a]]] = Lambda[UniqueIndexApi#ClientQuery ~> Lambda[a => Option[Alg[a]]]] {
        c => api.clientQueryRuntimeInject(I.asInstanceOf[CopK.Inject[api.ClientQuery, Alg]])(c).orElse(L.runtimeInject(c))
      }
    }
}

trait ClientEventRuntimeInject[L <: TList, Alg <: Cop[_]] {
  def apply(e: UniqueIndexApi#ClientEvent): Option[Alg]
}

object ClientEventRuntimeInject {
  implicit def base[Alg <: Cop[_]]: ClientEventRuntimeInject[TNil, Alg] = new ClientEventRuntimeInject[TNil, Alg] {
    override def apply(e: UniqueIndexApi#ClientEvent): Option[Alg] = None
  }

  implicit def induct[A <: UniqueIndexApi, L <: TList, Alg <: Cop[_]](implicit api: A, L: ClientEventRuntimeInject[L, Alg], I: Cop.Inject[A#ClientEventType, Alg]): ClientEventRuntimeInject[A ::: L, Alg] =
    new ClientEventRuntimeInject[A ::: L, Alg] {
      override def apply(e: UniqueIndexApi#ClientEvent): Option[Alg] =
        api.clientEventRuntimeInject(e)(I.asInstanceOf[Cop.Inject[api.ClientEvent, Alg]]).orElse(L(e))
    }
}