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

  implicit def eitherTMonadFree[M[_]: Monad, F[_], E](implicit M: MonadFree[M, F]): MonadFree[EitherT[E, M, *], F] = new MonadFree[EitherT[E, M, *], F] {
    override def liftF[A](fa: F[A]): EitherT[E, M, A] = M.liftF(fa).liftM[EitherT[E, *[_], *]]
  }

  implicit def rwstMonadFree[M[_]: Monad, F[_], R, W: Monoid, S](implicit M: MonadFree[M, F]): MonadFree[RWST[R, W, S, M, *], F] = new MonadFree[RWST[R, W, S, M, *], F] {
    override def liftF[A](fa: F[A]): RWST[R, W, S, M, A] = M.liftF(fa).liftM[RWST[R, W, S, *[_], *]]
  }

  implicit def stateMonadFree[M[_]: Monad, F[_], S](implicit M: MonadFree[M, F]): MonadFree[StateT[S, M, *], F] = new MonadFree[StateT[S, M, *], F] {
    override def liftF[A](fa: F[A]): StateT[S, M, A] = M.liftF(fa).liftM[StateT[S, *[_], *]]
  }
}

object MonadFree extends MonadFreeInstances  {
  def apply[M[_], F[_]](implicit M: MonadFree[M, F]): MonadFree[M, F] = M
}