package io.digitalmagic.akka.dsl

trait StringRepresentable[T] {
  def asString(v: T): String
  def fromString(s: String): Option[T]
}

object StringRepresentable {
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
}
