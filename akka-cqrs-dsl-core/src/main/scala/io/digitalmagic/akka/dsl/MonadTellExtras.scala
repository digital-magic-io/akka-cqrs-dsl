package io.digitalmagic.akka.dsl

import scalaz._
import Scalaz._

trait MonadTellExtras {
  implicit def eitherTMonadTell[F[_], W, E](implicit T: MonadTell[F, W]): MonadTell[EitherT[F, E, *], W] = EitherT.monadTell
  implicit def rwstMonadTell[F[_], R, W1, W2: Monoid, S](implicit T: MonadTell[F, W1]): MonadTell[RWST[F, R, W2, S, *], W1] = new MonadTell[RWST[F, R, W2, S, *], W1] {
    override def writer[A](w: W1, a: A): RWST[F, R, W2, S, A] = T.writer(w, a).liftM[RWST[*[_], R, W2, S, *]]
    override def point[A](a: => A): RWST[F, R, W2, S, A] = T.point(a).liftM[RWST[*[_], R, W2, S, *]]
    override def bind[A, B](fa: RWST[F, R, W2, S, A])(f: A => RWST[F, R, W2, S, B]): RWST[F, R, W2, S, B] = fa flatMap f
  }
  implicit def stateMonadTell[F[_], W, S](implicit T: MonadTell[F, W]): MonadTell[StateT[F, S, *], W] = new MonadTell[StateT[F, S, *], W] {
    override def writer[A](w: W, a: A): StateT[F, S, A] = T.writer(w, a).liftM[StateT[*[_], S, *]]
    override def point[A](a: => A): StateT[F, S, A] = T.point(a).liftM[StateT[*[_], S, *]]
    override def bind[A, B](fa: StateT[F, S, A])(f: A => StateT[F, S, B]): StateT[F, S, B] = fa flatMap f
  }
}

object MonadTellExtras extends MonadTellExtras
