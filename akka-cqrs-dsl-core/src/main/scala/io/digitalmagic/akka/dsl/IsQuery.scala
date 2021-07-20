package io.digitalmagic.akka.dsl

import io.digitalmagic.coproduct.TListK.:::
import io.digitalmagic.coproduct.{CopK, TListK, TNilK}
import io.digitalmagic.akka.dsl.API.Query

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
