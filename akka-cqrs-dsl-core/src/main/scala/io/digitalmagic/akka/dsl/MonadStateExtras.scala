package io.digitalmagic.akka.dsl

import scalaz._
import scalaz.Scalaz._

trait MonadStateExtras {
  implicit def rwstMonadState[F[_], R, W: Monoid, S1, S2](implicit S: MonadState[F, S1]): MonadState[RWST[R, W, S2, F, *], S1] = new MonadState[RWST[R, W, S2, F, *], S1] {
    override def init: RWST[R, W, S2, F, S1] = S.init.liftM[RWST[R, W, S2, *[_], *]]
    override def get: RWST[R, W, S2, F, S1] = S.get.liftM[RWST[R, W, S2, *[_], *]]
    override def put(s: S1): RWST[R, W, S2, F, Unit] = S.put(s).liftM[RWST[R, W, S2, *[_], *]]
    override def point[A](a: => A): RWST[R, W, S2, F, A] = S.point(a).liftM[RWST[R, W, S2, *[_], *]]
    override def bind[A, B](fa: RWST[R, W, S2, F, A])(f: A => RWST[R, W, S2, F, B]): RWST[R, W, S2, F, B] = fa flatMap f
  }
}

object MonadStateExtras extends MonadStateExtras