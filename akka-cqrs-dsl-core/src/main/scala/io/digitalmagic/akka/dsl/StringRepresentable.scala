package io.digitalmagic.akka.dsl

import java.util.UUID

import scalaz.InvariantFunctor
import scalaz.Isomorphism._

import scala.util.control.Exception._

trait StringRepresentable[T] {
  def asString(v: T): String
  def fromString(s: String): Option[T]
}

trait IsomorphismStringRepresentable[F, G] extends StringRepresentable[F] {
  implicit def G: StringRepresentable[G]
  def iso: F <=> G
  override def asString(v: F): String = G.asString(iso.to(v))
  override def fromString(s: String): Option[F] = G.fromString(s).map(iso.from(_))
}

object StringRepresentable {
  @inline def apply[F](implicit F: StringRepresentable[F]): StringRepresentable[F] = F

  implicit val stringStringRepresentable: StringRepresentable[String] = new StringRepresentable[String] {
    override def asString(v: String): String = v
    override def fromString(s: String): Option[String] = Some(s)
  }

  implicit val byteStringRepresentable: StringRepresentable[Byte] = new StringRepresentable[Byte] {
    override def asString(v: Byte): String = v.toString
    override def fromString(s: String): Option[Byte] = catching(classOf[NumberFormatException]) opt s.toByte
  }

  implicit val shortStringRepresentable: StringRepresentable[Short] = new StringRepresentable[Short] {
    override def asString(v: Short): String = v.toString
    override def fromString(s: String): Option[Short] = catching(classOf[NumberFormatException]) opt s.toShort
  }

  implicit val intStringRepresentable: StringRepresentable[Int] = new StringRepresentable[Int] {
    override def asString(v: Int): String = v.toString
    override def fromString(s: String): Option[Int] = catching(classOf[NumberFormatException]) opt s.toInt
  }

  implicit val longStringRepresentable: StringRepresentable[Long] = new StringRepresentable[Long] {
    override def asString(v: Long): String = v.toString
    override def fromString(s: String): Option[Long] = catching(classOf[NumberFormatException]) opt s.toLong
  }

  implicit val uuidStringRepresentable: StringRepresentable[UUID] = new StringRepresentable[UUID] {
    override def asString(v: UUID): String = v.toString
    override def fromString(s: String): Option[UUID] = catching(classOf[IllegalArgumentException]) opt UUID.fromString(s)
  }

  def fromIso[F, G](D: F <=> G)(implicit S: StringRepresentable[G]): StringRepresentable[F] =
    new IsomorphismStringRepresentable[F, G] {
      override implicit def G: StringRepresentable[G] = S
      override def iso: F <=> G = D
    }

  implicit val stringRepresentableInvariantFunctor: InvariantFunctor[StringRepresentable] = new InvariantFunctor[StringRepresentable] {
    override def xmap[A, B](ma: StringRepresentable[A], f: A => B, g: B => A): StringRepresentable[B] = new StringRepresentable[B] {
      override def asString(v: B): String = ma.asString(g(v))
      override def fromString(s: String): Option[B] = ma.fromString(s).map(f)
    }
  }
}
