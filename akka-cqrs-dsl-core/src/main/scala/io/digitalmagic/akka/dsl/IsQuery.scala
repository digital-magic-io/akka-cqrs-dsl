package io.digitalmagic.akka.dsl

import io.digitalmagic.akka.dsl.API.Query
import iotaz._
import TListK.:::

import scala.annotation.implicitNotFound

sealed trait IsQueryHelper[LL <: TListK]

object IsQueryHelper {
  implicit val base: IsQueryHelper[TNilK] = null
  implicit def induct[F[A] <: Query[A], LL <: TListK](implicit LL: IsQueryHelper[LL]): IsQueryHelper[F ::: LL] = null
}

@implicitNotFound("Only queries can be used here")
sealed trait IsQuery[T[_]]

object IsQuery {
  implicit def isQueryForCopK[LL <: TListK](implicit ev: IsQueryHelper[LL]): IsQuery[CopK[LL, *]] = null
}
