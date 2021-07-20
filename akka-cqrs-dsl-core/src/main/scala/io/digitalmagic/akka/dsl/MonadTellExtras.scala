package io.digitalmagic.akka.dsl

import scalaz._
import Scalaz._

trait MonadTellExtras {
  implicit def eitherTMonadTell[F[_], W, E](implicit T: MonadTell[F, W]): MonadTell[EitherT[E, F, *], W] = EitherT.monadTell
  implicit def rwstMonadTell[F[_], R, W1, W2: Monoid, S](implicit T: MonadTell[F, W1]): MonadTell[RWST[R, W2, S, F, *], W1] = new MonadTell[RWST[R, W2, S, F, *], W1] {
    override def writer[A](w: W1, a: A): RWST[R, W2, S, F, A] = T.writer(w, a).liftM[RWST[R, W2, S, *[_], *]]
    override def point[A](a: => A): RWST[R, W2, S, F, A] = T.point(a).liftM[RWST[R, W2, S, *[_], *]]
    override def bind[A, B](fa: RWST[R, W2, S, F, A])(f: A => RWST[R, W2, S, F, B]): RWST[R, W2, S, F, B] = fa flatMap f
  }
  implicit def stateMonadTell[F[_], W, S](implicit T: MonadTell[F, W]): MonadTell[StateT[S, F, *], W] = new MonadTell[StateT[S, F, *], W] {
    override def writer[A](w: W, a: A): StateT[S, F, A] = T.writer(w, a).liftM[StateT[S, *[_], *]]
    override def point[A](a: => A): StateT[S, F, A] = T.point(a).liftM[StateT[S, *[_], *]]
    override def bind[A, B](fa: StateT[S, F, A])(f: A => StateT[S, F, B]): StateT[S, F, B] = fa flatMap f
  }
}

object MonadTellExtras extends MonadTellExtras
