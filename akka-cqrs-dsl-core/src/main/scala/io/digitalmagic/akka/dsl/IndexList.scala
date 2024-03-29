package io.digitalmagic.akka.dsl

import io.digitalmagic.coproduct.TList.::
import io.digitalmagic.coproduct.TListK.:::
import io.digitalmagic.coproduct.{Cop, CopK, TList, TListK, TNil, TNilK}
import scalaz._

trait ClientEventInterpreterS[E, T] {
  def apply(e: T, s: ClientIndexesStateMap):  ClientIndexesStateMap
}

object ClientEventInterpreterS {
  implicit def clientEventInterpreter[E, I <: UniqueIndexApi, T](implicit api: UniqueIndexApi.ClientEventAux[E, I, T]): ClientEventInterpreterS[E, T] = (e, s) => s.process(api)(e)
}

sealed trait IndexList {
  type List <: TList
  type AlgebraList <: TListK
  type Algebra[A] <: CopK[AlgebraList, A]
  type ClientAlgebraList <: TListK
  type ClientAlgebra[A] <: CopK[ClientAlgebraList, A]
  type ClientEventList <: TList
  type ClientEventAlgebra <: Cop[ClientEventList]
  type LocalAlgebraList <: TListK
  type LocalAlgebra[A] <: CopK[LocalAlgebraList, A]

  type + [I <: UniqueIndexApi] = WithIndex[I :: List, I#IndexApiType ::: AlgebraList, I#ClientQueryType ::: ClientAlgebraList, I#ClientEventType :: ClientEventList, I#LocalQueryType ::: LocalAlgebraList]
}

final class EmptyIndexList extends IndexList {
  type List = TNil
  type AlgebraList = TNilK
  type Algebra[A] = CopK[AlgebraList, A]
  type ClientAlgebraList = TNilK
  type ClientAlgebra[A] = CopK[ClientAlgebraList, A]
  type ClientEventList = TNil
  type ClientEventAlgebra = Cop[ClientEventList]
  type LocalAlgebraList = TNilK
  type LocalAlgebra[A] = CopK[LocalAlgebraList, A]
}

final class WithIndex[L <: TList, AL <: TListK, CAL <: TListK, CEL <: TList, LAL <: TListK] extends IndexList {
  type List = L
  type AlgebraList = AL
  type Algebra[A] = CopK[AlgebraList, A]
  type ClientAlgebraList = CAL
  type ClientAlgebra[A] = CopK[ClientAlgebraList, A]
  type ClientEventList = CEL
  type ClientEventAlgebra = Cop[ClientEventList]
  type LocalAlgebraList = LAL
  type LocalAlgebra[A] = CopK[LocalAlgebraList, A]
}

trait ClientRuntime[L <: TList, IL <: IndexList] {
  def injectQuery: UniqueIndexApi#ClientQuery ~> Lambda[a => Option[IL#ClientAlgebra[a]]]
  def injectEvent(e: UniqueIndexApi#ClientEvent): Option[IL#ClientEventAlgebra]
  def hasIndex(api: UniqueIndexApi): Boolean
}

object ClientRuntime {
  def apply[Index <: IndexList](implicit clientRuntime: ClientRuntime[Index#List, Index]): ClientRuntime[Index#List, Index] = clientRuntime

  implicit def base[E, IL <: IndexList]: ClientRuntime[TNil, IL] = new ClientRuntime[TNil, IL] {
    override def injectQuery: UniqueIndexApi#ClientQuery ~> Lambda[a => Option[IL#ClientAlgebra[a]]] = Lambda[UniqueIndexApi#ClientQuery ~> Lambda[a => Option[IL#ClientAlgebra[a]]]] {
      _ => None
    }
    override def injectEvent(e: UniqueIndexApi#ClientEvent): Option[IL#ClientEventAlgebra] = None
    override def hasIndex(api: UniqueIndexApi): Boolean = false
  }

  implicit def induct[E,
                      I <: UniqueIndexApi,
                      L <: TList,
                      IL <: IndexList,
                      T[X] >: I#ClientQuery[X] <: I#ClientQuery[X],
                      U
                     ](implicit
                       I: I,
                       L: ClientRuntime[L, IL],
                       QA: UniqueIndexApi.ClientQueryAux[E, I, T],
                       QI: CopK.Inject[T, IL#ClientAlgebra],
                       EA: UniqueIndexApi.ClientEventAux[E, I, U],
                       EI: Cop.Inject[U, IL#ClientEventAlgebra]
                     ): ClientRuntime[I :: L, IL] =

    new ClientRuntime[I :: L, IL] {
      override def injectQuery: UniqueIndexApi#ClientQuery ~> Lambda[a => Option[IL#ClientAlgebra[a]]] = Lambda[UniqueIndexApi#ClientQuery ~> Lambda[a => Option[IL#ClientAlgebra[a]]]] {
        c => QA.clientQueryRuntimeInject(QI)(c).orElse(L.injectQuery(c))
      }
      override def injectEvent(e: UniqueIndexApi#ClientEvent): Option[IL#ClientEventAlgebra] =
        EA.clientEventRuntimeInject(e).orElse(L.injectEvent(e))
      override def hasIndex(api: UniqueIndexApi): Boolean = api == I || L.hasIndex(api)
    }
}
