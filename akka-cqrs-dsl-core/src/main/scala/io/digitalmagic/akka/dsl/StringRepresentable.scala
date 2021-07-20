package io.digitalmagic.akka.dsl

import java.util.UUID

import scalaz._
import scalaz.Isomorphism._
import scalaz.Scalaz._

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
      override def G: StringRepresentable[G] = S
      override def iso: F <=> G = D
    }

  implicit val stringRepresentableInvariantFunctor: InvariantFunctor[StringRepresentable] = new InvariantFunctor[StringRepresentable] {
    override def xmap[A, B](ma: StringRepresentable[A], f: A => B, g: B => A): StringRepresentable[B] = new StringRepresentable[B] {
      override def asString(v: B): String = ma.asString(g(v))
      override def fromString(s: String): Option[B] = ma.fromString(s).map(f)
    }
  }

  implicit def pairStringRepresentable[A: StringRepresentable, B: StringRepresentable]: StringRepresentable[(A, B)] = new StringRepresentable[(A, B)] {
    private val aSR = implicitly[StringRepresentable[A]]
    private val bSR = implicitly[StringRepresentable[B]]

    override def asString(v: (A, B)): String = {
      def appendEscaped(sb: StringBuilder, ch: Char): StringBuilder = ch match {
        case '\\' => sb.append("\\\\")
        case ','  => sb.append("\\,")
        case _    => sb.append(ch)
      }
      val a = aSR.asString(v._1)
      val b = bSR.asString(v._2)
      b.foldLeft(a.foldLeft(new StringBuilder())(appendEscaped) .append(','))(appendEscaped).mkString
    }

    override def fromString(s: String): Option[(A, B)] = {
      case class State(first: StringBuilder = new StringBuilder(), second: Option[StringBuilder] = None, escapeSequence: Boolean = false, failed: Boolean = false) {
        def apply(ch: Char): State = (this, ch) match {
          case (State(_, _,       _,     true),  _)    => this
          case (State(_, _,       false, false), '\\') => copy(escapeSequence = true)
          case (State(_, Some(_), false, false), ',')  => copy(failed = true)
          case (State(_, Some(_), false, false), _)    => copy(second = second.map(_.append(ch)))
          case (State(_, Some(_), true,  false), '\\') => copy(second = second.map(_.append(ch)), escapeSequence = false)
          case (State(_, Some(_), true,  false), ',')  => copy(second = second.map(_.append(ch)), escapeSequence = false)
          case (State(_, Some(_), true,  false), _)    => copy(failed = true)
          case (State(_, None,    false, false), ',')  => copy(second = Some(new StringBuilder()))
          case (State(_, None,    false, false), _)    => copy(first = first.append(ch))
          case (State(_, None,    true,  false), '\\') => copy(first = first.append(ch), escapeSequence = false)
          case (State(_, None,    true,  false), ',')  => copy(first = first.append(ch), escapeSequence = false)
          case (State(_, None,    true,  false), _)    => copy(failed = true)
        }

        def result: Option[(A, B)] = (escapeSequence || failed, second) match {
          case (false, Some(snd)) => (aSR.fromString(first.mkString) |@| bSR.fromString(snd.mkString))((_, _))
          case _ => None
        }
      }
      s.foldLeft(State())(_(_)).result
    }
  }
}
