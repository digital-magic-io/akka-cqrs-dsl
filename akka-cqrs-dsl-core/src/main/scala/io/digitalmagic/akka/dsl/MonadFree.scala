package io.digitalmagic.akka.dsl

import scalaz._
import Scalaz._

trait MonadFree[M[_], F[_]] {
  def liftF[A](fa: F[A]): M[A]
}

trait MonadFreeInstances {
  implicit def freeTMonadFree[M[_]: Applicative, F[_]: Functor]: MonadFree[FreeT[F, M, *], F] = new MonadFree[FreeT[F, M, *], F] {
    override def liftF[A](fa: F[A]): FreeT[F, M, A] = FreeT.liftF[F, M, A](fa)
  }

  implicit def monadFreeMonadFree[M[_]: Monad, F[_], F2[_]: Functor](implicit M: MonadFree[M, F]): MonadFree[FreeT[F2, M, *], F] = new MonadFree[FreeT[F2, M, *], F] {
    override def liftF[A](fa: F[A]): FreeT[F2, M, A] = M.liftF(fa).liftM[FreeT[F2, *[_], *]]
  }

  implicit def eitherTMonadFree[M[_]: Monad, F[_], E](implicit M: MonadFree[M, F]): MonadFree[EitherT[M, E, *], F] = new MonadFree[EitherT[M, E, *], F] {
    override def liftF[A](fa: F[A]): EitherT[M, E, A] = M.liftF(fa).liftM[EitherT[*[_], E, *]]
  }

  implicit def rwstMonadFree[M[_]: Monad, F[_], R, W: Monoid, S](implicit M: MonadFree[M, F]): MonadFree[RWST[M, R, W, S, *], F] = new MonadFree[RWST[M, R, W, S, *], F] {
    override def liftF[A](fa: F[A]): RWST[M, R, W, S, A] = M.liftF(fa).liftM[RWST[*[_], R, W, S, *]]
  }
}

object MonadFree extends MonadFreeInstances  {
  def apply[M[_], F[_]](implicit M: MonadFree[M, F]): MonadFree[M, F] = M
}