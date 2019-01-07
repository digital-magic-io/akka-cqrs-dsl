package io.digitalmagic.akka.dsl

import io.digitalmagic.akka.dsl.API.Query
import iotaz._
import TListK.:::

import scala.annotation.implicitNotFound

sealed trait IsQueryHelper[LL <: TListK] {
}

object IsQueryHelper {
  private val isQueryHelperSingleton = new IsQueryHelper[TNilK] {}
  implicit def base[A]: IsQueryHelper[TNilK] = isQueryHelperSingleton.asInstanceOf[IsQueryHelper[TNilK] {}]
  implicit def induct[F[_], LL <: TListK](implicit ev: IsQuery[F], LL: IsQueryHelper[LL]): IsQueryHelper[F ::: LL] = isQueryHelperSingleton.asInstanceOf[IsQueryHelper[F ::: LL]]
}

@implicitNotFound("Only queries can be used here")
sealed trait IsQuery[T[_]]

object IsQuery {
  private val isQuerySingleton = new IsQuery[List] {}

  implicit def isQuery[T[A] <: Query[A]]: IsQuery[T] = isQuerySingleton.asInstanceOf[IsQuery[T]]
  implicit def isQueryForCopK[LL <: TListK](implicit ev: IsQueryHelper[LL]): IsQuery[CopK[LL, ?]] = isQuerySingleton.asInstanceOf[IsQuery[CopK[LL, ?]]]
}
