package io.digitalmagic.akka.dsl

import scalaz.InvariantFunctor

trait StringRepresentable[T] {
  def asString(v: T): String
  def fromString(s: String): Option[T]
}

object StringRepresentable {
  @inline def apply[F](implicit F: StringRepresentable[F]): StringRepresentable[F] = F

  implicit def stringStringRepresentable: StringRepresentable[String] = new StringRepresentable[String] {
    override def asString(v: String): String = v
    override def fromString(s: String): Option[String] = Some(s)
  }

  implicit def intStringRepresentable: StringRepresentable[Int] = new StringRepresentable[Int] {
    override def asString(v: Int): String = v.toString
    override def fromString(s: String): Option[Int] = try {
      Some(s.toInt)
    } catch {
      case _: NumberFormatException => None
    }
  }

  implicit def stringRepresentableInvariantFunctor: InvariantFunctor[StringRepresentable] = new InvariantFunctor[StringRepresentable] {
    override def xmap[A, B](ma: StringRepresentable[A], f: A => B, g: B => A): StringRepresentable[B] = new StringRepresentable[B] {
      override def asString(v: B): String = ma.asString(g(v))
      override def fromString(s: String): Option[B] = ma.fromString(s).map(f)
    }
  }
}
