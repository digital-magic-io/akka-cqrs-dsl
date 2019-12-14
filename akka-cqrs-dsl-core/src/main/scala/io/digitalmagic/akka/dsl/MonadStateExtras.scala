package io.digitalmagic.akka.dsl

import scalaz._
import scalaz.Scalaz._

trait MonadStateExtras {
  implicit def rwstMonadState[F[_], R, W: Monoid, S1, S2](implicit S: MonadState[F, S1]): MonadState[RWST[F, R, W, S2, *], S1] = new MonadState[RWST[F, R, W, S2, *], S1] {
    override def init: RWST[F, R, W, S2, S1] = S.init.liftM[RWST[*[_], R, W, S2, *]]
    override def get: RWST[F, R, W, S2, S1] = S.get.liftM[RWST[*[_], R, W, S2, *]]
    override def put(s: S1): RWST[F, R, W, S2, Unit] = S.put(s).liftM[RWST[*[_], R, W, S2, *]]
    override def point[A](a: => A): RWST[F, R, W, S2, A] = S.point(a).liftM[RWST[*[_], R, W, S2, *]]
    override def bind[A, B](fa: RWST[F, R, W, S2, A])(f: A => RWST[F, R, W, S2, B]): RWST[F, R, W, S2, B] = fa flatMap f
  }
}

object MonadStateExtras extends MonadStateExtras