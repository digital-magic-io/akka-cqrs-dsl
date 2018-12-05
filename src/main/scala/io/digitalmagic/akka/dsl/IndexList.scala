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

trait ClientQueryRuntimeInject[L <: TList, Alg[X] <: CopK[_, X]] {
  def runtimeInject: UniqueIndexApi#ClientQuery ~> Lambda[a => Option[Alg[a]]]
}

object ClientQueryRuntimeInject {
  implicit def base[E, Alg[X] <: CopK[_, X]]: ClientQueryRuntimeInject[TNil, Alg] = new ClientQueryRuntimeInject[TNil, Alg] {
    override def runtimeInject: UniqueIndexApi#ClientQuery ~> Lambda[a => Option[Alg[a]]] = Lambda[UniqueIndexApi#ClientQuery ~> Lambda[a => Option[Alg[a]]]] {
      _ => None
    }
  }

  implicit def induct[E, I <: UniqueIndexApi, L <: TList, Alg[X] <: CopK[_, X], T[X] >: I#ClientQuery[X] <: I#ClientQuery[X]](implicit
                                                                                                                              api: UniqueIndexApi.ClientQueryAux[E, I, T],
                                                                                                                              L: ClientQueryRuntimeInject[L, Alg],
                                                                                                                              I: CopK.Inject[T, Alg]): ClientQueryRuntimeInject[I ::: L, Alg] =
    new ClientQueryRuntimeInject[I ::: L, Alg] {
      override def runtimeInject: UniqueIndexApi#ClientQuery ~> Lambda[a => Option[Alg[a]]] = Lambda[UniqueIndexApi#ClientQuery ~> Lambda[a => Option[Alg[a]]]] {
        c => api.clientQueryRuntimeInject(I)(c).orElse(L.runtimeInject(c))
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

  implicit def induct[E, I <: UniqueIndexApi, L <: TList, Alg <: Cop[_], T](implicit
                                                                            api: UniqueIndexApi.ClientEventAux[E, I, T],
                                                                            L: ClientEventRuntimeInject[L, Alg],
                                                                            I: Cop.Inject[T, Alg]): ClientEventRuntimeInject[I ::: L, Alg] =
    new ClientEventRuntimeInject[I ::: L, Alg] {
      override def apply(e: UniqueIndexApi#ClientEvent): Option[Alg] =
        api.clientEventRuntimeInject(e).orElse(L(e))
    }
}